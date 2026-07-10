package forge.anvil;

import com.google.protobuf.ByteString;

import forge.ai.anvil.AnvilBridge;
import forge.ai.anvil.CastPlanAnswer;
import forge.ai.anvil.Obs;
import forge.anvil.bridge.v0.AnswerShape;
import forge.anvil.bridge.v0.CastPlan;
import forge.anvil.bridge.v0.EntityRef;
import forge.anvil.bridge.v0.Constraints;
import forge.anvil.bridge.v0.DecisionBridgeGrpc;
import forge.anvil.bridge.v0.DecisionRequest;
import forge.anvil.bridge.v0.DecisionResponse;
import forge.anvil.bridge.v0.GameEnd;
import forge.anvil.bridge.v0.GameStart;
import forge.anvil.bridge.v0.IndexList;
import forge.anvil.bridge.v0.Option;
import forge.anvil.bridge.v0.ServerMsg;
import forge.anvil.bridge.v0.WorkerHello;
import forge.anvil.bridge.v0.WorkerMsg;
import forge.util.MyRandom;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * gRPC implementation of AnvilBridge (bridge-protocol-v0): one long-lived
 * bidirectional stream, one outstanding request at a time (the game thread
 * blocks). At M0 every answer is pre-drawn from the game's seeded RNG and sent
 * as echo_answer — the server echoes it back, so gRPC-arm games are
 * bit-identical to local-arm games and the throughput delta isolates
 * serialization + transport. On deadline or stream failure the pre-drawn
 * answer is used locally (counted; the run stays alive and deterministic).
 */
public final class GrpcBridge implements AnvilBridge {
    private static final int DEADLINE_MS = 5000;

    private final ManagedChannel channel;
    private final StreamObserver<WorkerMsg> out;
    private final LinkedBlockingQueue<ServerMsg> in = new LinkedBlockingQueue<>();
    private final Set<String> serverTags;
    private final boolean oneShotCast;
    private long seq;
    private String gameId = "";
    private int transportFailures;

    public GrpcBridge(String host, int port, String workerId, String forkCommit) {
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        out = DecisionBridgeGrpc.newStub(channel).session(new StreamObserver<ServerMsg>() {
            @Override
            public void onNext(ServerMsg msg) {
                in.add(msg);
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("[GrpcBridge] stream error: " + t);
            }

            @Override
            public void onCompleted() {
            }
        });
        out.onNext(WorkerMsg.newBuilder().setHello(WorkerHello.newBuilder()
                .setProtocolVersion(0).setWorkerId(workerId).setForkCommit(forkCommit)).build());
        ServerMsg hello = await();
        if (hello == null || !hello.hasHello()) {
            throw new IllegalStateException("Anvil decision server handshake failed");
        }
        serverTags = Set.copyOf(hello.getHello().getBridgedTagsList());
        oneShotCast = hello.getHello().getOneShotCast();
    }

    /** Server-driven coverage: the tag set this session answers over the wire. */
    public Set<String> serverBridgedTags() {
        return serverTags;
    }

    public int transportFailures() {
        return transportFailures;
    }

    private ServerMsg await() {
        try {
            return in.poll(DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private DecisionResponse roundTrip(String tag, AnswerShape shape, List<String> optionLabels,
            Constraints constraints, DecisionResponse echo) {
        DecisionRequest.Builder req = DecisionRequest.newBuilder()
                .setGameId(gameId).setDecisionSeq(++seq).setDecisionTag(tag)
                .setShape(shape).setDeadlineMs(DEADLINE_MS).setEchoAnswer(echo);
        if (constraints != null) {
            req.setConstraints(constraints);
        }
        if (optionLabels != null) {
            for (int i = 0; i < optionLabels.size(); i++) {
                String label = optionLabels.get(i);
                req.addOptions(Option.newBuilder().setId(i).setLabel(label == null ? "" : label));
            }
        }
        out.onNext(WorkerMsg.newBuilder().setRequest(req).build());
        ServerMsg msg = await();
        if (msg == null || !msg.hasResponse()) {
            transportFailures++;
            System.err.println("[GrpcBridge] deadline/failure on " + tag + " seq=" + seq
                    + " (total " + transportFailures + "); using local answer");
            return echo;
        }
        return msg.getResponse();
    }

    @Override
    public int selectOne(String tag, List<String> optionLabels) {
        int n = optionLabels.size();
        int local = n <= 1 ? 0 : MyRandom.getRandom().nextInt(n);
        DecisionResponse resp = roundTrip(tag, AnswerShape.SELECT_ONE, optionLabels, null,
                DecisionResponse.newBuilder().setIndex(local).build());
        return resp.getIndex();
    }

    @Override
    public int[] selectK(String tag, int n, int k) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        for (int i = 0; i < k && i < n; i++) {
            int j = i + MyRandom.getRandom().nextInt(n - i);
            int tmp = all[i];
            all[i] = all[j];
            all[j] = tmp;
        }
        IndexList.Builder local = IndexList.newBuilder();
        java.util.Arrays.stream(all, 0, Math.min(k, n)).sorted().forEach(local::addIndices);
        DecisionResponse resp = roundTrip(tag, AnswerShape.SELECT_K, null,
                Constraints.newBuilder().setK(Math.min(k, n)).setMax(n).build(),
                DecisionResponse.newBuilder().setIndices(local).build());
        return resp.getIndices().getIndicesList().stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public boolean bool(String tag) {
        boolean local = MyRandom.getRandom().nextBoolean();
        DecisionResponse resp = roundTrip(tag, AnswerShape.BOOL, null, null,
                DecisionResponse.newBuilder().setFlag(local).build());
        return resp.getFlag();
    }

    @Override
    public int intInRange(String tag, int min, int max) {
        int local = max <= min ? min : min + MyRandom.getRandom().nextInt(max - min + 1);
        DecisionResponse resp = roundTrip(tag, AnswerShape.INT_IN_RANGE, null,
                Constraints.newBuilder().setMin(min).setMax(max).build(),
                DecisionResponse.newBuilder().setValue(local).build());
        return (int) resp.getValue();
    }

    @Override
    public void gameStart(String id, long seed) {
        gameId = id;
        GameStart.Builder gs = GameStart.newBuilder()
                .setGameId(id).setSeed(seed).setFormatTag("mtg.commander");
        // M1: observation game header (AnvilRun calls Obs.startGame first, so
        // it exists whenever obs logging is on; absent -> M0-shape session).
        String header = Obs.lastHeaderForBridge();
        if (header != null) {
            gs.setHeader(ByteString.copyFromUtf8(header));
        }
        out.onNext(WorkerMsg.newBuilder().setGameStart(gs).build());
    }

    /**
     * M1 one-shot cast. Active only when the server declared one_shot_cast in
     * its hello; otherwise null routes the caller to the M0 selectOne path.
     * Transport failure or server fallback answers PASS (counted): an eval
     * arm must never silently substitute random or heuristic play for the
     * model on a bridged tag.
     */
    @Override
    public CastPlanAnswer priorityCastPlan(String tag, List<String> optionLabels,
            String observation) {
        if (!oneShotCast) {
            return null;
        }
        DecisionRequest.Builder req = DecisionRequest.newBuilder()
                .setGameId(gameId).setDecisionSeq(++seq).setDecisionTag(tag)
                .setShape(AnswerShape.CONSTRUCT).setDeadlineMs(DEADLINE_MS);
        if (optionLabels != null) {
            for (int i = 0; i < optionLabels.size(); i++) {
                String label = optionLabels.get(i);
                req.addOptions(Option.newBuilder().setId(i).setLabel(label == null ? "" : label));
            }
        }
        if (observation != null) {
            req.setObservation(ByteString.copyFromUtf8(observation));
        }
        out.onNext(WorkerMsg.newBuilder().setRequest(req).build());
        ServerMsg msg = await();
        if (msg == null || !msg.hasResponse()) {
            transportFailures++;
            System.err.println("[GrpcBridge] deadline/failure on " + tag + " seq=" + seq
                    + " (total " + transportFailures + "); one-shot answers PASS");
            return pass();
        }
        DecisionResponse resp = msg.getResponse();
        if (resp.getFallback() || !resp.hasConstruct() || !resp.getConstruct().hasCastPlan()) {
            transportFailures++;
            return pass();
        }
        CastPlan cp = resp.getConstruct().getCastPlan();
        java.util.List<CastPlanAnswer.Ref> refs =
                new java.util.ArrayList<>(cp.getTargetRefsCount());
        for (EntityRef r : cp.getTargetRefsList()) {
            if (r.getRefCase() == EntityRef.RefCase.PLAYER) {
                refs.add(new CastPlanAnswer.Ref(true, r.getPlayer(), -1, false));
            } else {
                refs.add(new CastPlanAnswer.Ref(false, -1, (int) r.getEntity(), r.getNs() == 1));
            }
        }
        return new CastPlanAnswer((int) cp.getSpellOption(), cp.getHostLevel(), refs,
                cp.getHasX(), (int) cp.getXValue());
    }

    private static CastPlanAnswer pass() {
        return new CastPlanAnswer(0, false, java.util.Collections.emptyList(), false, 0);
    }

    @Override
    public void gameEnd(String id, String winner, int turns, long wallMs) {
        out.onNext(WorkerMsg.newBuilder().setGameEnd(GameEnd.newBuilder()
                .setGameId(id).setWinner(winner == null ? "" : winner)
                .setTurns(Math.max(turns, 0)).setWallMs(wallMs)).build());
    }

    @Override
    public void close() {
        out.onCompleted();
        channel.shutdown();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
