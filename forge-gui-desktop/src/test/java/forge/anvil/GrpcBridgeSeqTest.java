package forge.anvil;

import forge.anvil.bridge.v0.DecisionBridgeGrpc;
import forge.anvil.bridge.v0.DecisionResponse;
import forge.anvil.bridge.v0.ServerHello;
import forge.anvil.bridge.v0.ServerMsg;
import forge.anvil.bridge.v0.WorkerMsg;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Probe: can a late or mismatched response ever cross decision (or game)
 * boundaries on the gRPC bridge?
 *
 * Provenance: Talor-A/anvil#9 — the decision deadline did not cancel the
 * in-flight request, and the shared response queue handed the late response
 * to the NEXT waiter: every subsequent decision consumed its predecessor's
 * answer (off-by-one desync, silently crossing game boundaries). Observed
 * in the field as consecutive deadline lines in drillo6-c2t3 (seq 4089/4090)
 * followed by unlogged misassociation. The protocol doc's contract
 * (bridge-protocol-v0: "mismatched sequence is a protocol bug → worker
 * drains, game discarded, loud log") was never implemented.
 *
 * Contract pinned here: a deadline or a decision_seq mismatch POISONS the
 * stream — the failing decision throws, every later decision throws without
 * reading the queue, and a late response can never be returned as any
 * decision's answer.
 */
public class GrpcBridgeSeqTest {

    private Server server;
    private GrpcBridge bridge;
    private final ScheduledExecutorService delayer =
            Executors.newSingleThreadScheduledExecutor();

    /** Scripted decision server: onRequest(request-msg, responder). */
    private GrpcBridge connect(BiConsumer<WorkerMsg, StreamObserver<ServerMsg>> onRequest)
            throws Exception {
        DecisionBridgeGrpc.DecisionBridgeImplBase svc =
                new DecisionBridgeGrpc.DecisionBridgeImplBase() {
                    @Override
                    public StreamObserver<WorkerMsg> session(StreamObserver<ServerMsg> resp) {
                        return new StreamObserver<WorkerMsg>() {
                            @Override
                            public void onNext(WorkerMsg msg) {
                                if (msg.hasHello()) {
                                    resp.onNext(ServerMsg.newBuilder().setHello(
                                            ServerHello.newBuilder().setProtocolVersion(0)
                                                    .addBridgedTags("mtg.priority")).build());
                                } else if (msg.hasRequest()) {
                                    onRequest.accept(msg, resp);
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                            }

                            @Override
                            public void onCompleted() {
                                resp.onCompleted();
                            }
                        };
                    }
                };
        server = ServerBuilder.forPort(0).addService(svc).build().start();
        // Short deadline so the timeout path runs in test time, read at
        // construction (the knob is operational, not part of correctness).
        System.setProperty("anvil.bridge.deadline.ms", "300");
        return new GrpcBridge("localhost", server.getPort(), "test", "test");
    }

    @AfterMethod
    public void tearDown() throws Exception {
        System.clearProperty("anvil.bridge.deadline.ms");
        if (bridge != null) {
            try {
                bridge.close();
            } catch (Exception ignored) {
            }
            bridge = null;
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(2, TimeUnit.SECONDS);
            server = null;
        }
    }

    private static DecisionResponse answer(WorkerMsg req, int index) {
        return DecisionResponse.newBuilder()
                .setDecisionSeq(req.getRequest().getDecisionSeq()).setIndex(index).build();
    }

    @Test
    public void testHappyPathMatchedSeq() throws Exception {
        bridge = connect((req, resp) -> resp.onNext(
                ServerMsg.newBuilder().setResponse(answer(req, 1)).build()));
        bridge.gameStart("g0", 42L);
        Assert.assertEquals(bridge.selectOne("mtg.priority", List.of("pass", "cast")), 1);
        Assert.assertEquals(bridge.selectOne("mtg.priority", List.of("pass", "cast")), 1);
        Assert.assertFalse(bridge.poisoned());
    }

    @Test
    public void testLateResponseCannotCrossDecisionsOrGames() throws Exception {
        // First request: respond CORRECTLY but after the deadline (the #9
        // shape). Later requests: answer instantly with index 1.
        bridge = connect((req, resp) -> {
            if (req.getRequest().getDecisionSeq() == 1) {
                delayer.schedule(() -> resp.onNext(
                        ServerMsg.newBuilder().setResponse(answer(req, 7)).build()),
                        600, TimeUnit.MILLISECONDS);
            } else {
                resp.onNext(ServerMsg.newBuilder().setResponse(answer(req, 1)).build());
            }
        });
        bridge.gameStart("g0", 42L);

        // Decision 1 times out -> poisoned, game discarded (throws).
        Assert.assertThrows(GrpcBridge.BridgePoisonedException.class,
                () -> bridge.selectOne("mtg.priority", List.of("pass", "cast")));
        Assert.assertTrue(bridge.poisoned());
        Assert.assertEquals(bridge.transportFailures(), 1);

        // The late response (index 7) lands on the queue while we cross a
        // game boundary — pre-fix, the next decision would RETURN 7 as its
        // own answer. Post-fix it must throw without reading the queue.
        Thread.sleep(700);
        bridge.gameStart("g1", 43L);
        Assert.assertThrows(GrpcBridge.BridgePoisonedException.class,
                () -> bridge.selectOne("mtg.priority", List.of("pass", "cast")));
    }

    @Test
    public void testMismatchedSeqPoisons() throws Exception {
        bridge = connect((req, resp) -> resp.onNext(ServerMsg.newBuilder()
                .setResponse(DecisionResponse.newBuilder()
                        .setDecisionSeq(req.getRequest().getDecisionSeq() + 1)
                        .setIndex(0).build()).build()));
        bridge.gameStart("g0", 42L);
        Assert.assertThrows(GrpcBridge.BridgePoisonedException.class,
                () -> bridge.selectOne("mtg.priority", List.of("pass", "cast")));
        Assert.assertTrue(bridge.poisoned());
    }
}
