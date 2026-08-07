/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.gamemodes.match.input;

import forge.game.Game;
import forge.game.GameView;
import forge.game.event.GameEventAwaitingInput;
import forge.game.player.PlayerView;
import forge.util.IHasForgeLog;
import forge.player.PlayerControllerHuman;

import java.util.Observable;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * <p>
 * InputControl class.
 * </p>
 *
 * @author Forge
 * @version $Id: InputQueue.java 24769 2014-02-09 13:56:04Z Hellfish $
 */
public class InputQueue extends Observable implements IHasForgeLog {

    private final BlockingDeque<InputSynchronized> inputStack = new LinkedBlockingDeque<>();
    private final GameView gameView;

    public InputQueue(final GameView gameView, final InputProxy inputProxy) {
        this.gameView = gameView;
        addObserver(inputProxy);
    }

    public final void updateObservers() {
        setChanged();
        notifyObservers();
    }

    public final Input getInput() {
        return inputStack.isEmpty() ? null : inputStack.peek();
    }

    /*pfps sometimes this is being called twice for the same input */
    public final void removeInput(final Input inp) {
        final Input topMostInput = inputStack.isEmpty() ? null : inputStack.peek();

        if (topMostInput != inp) {
            System.out.println("Cannot remove input " + inp.getClass().getSimpleName() + " because it's not on top of stack. Stack = " + inputStack );
        } else if (topMostInput != null) {
            // if topMostInput is null then it means the inputstack is already empty, why this is called twice?
           inputStack.pop();
        }
        if (inputStack.isEmpty()) {
            // Safe off the game thread: the game thread is parked on this input's
            // latch until stop() counts it down, after removeInput returns.
            markAwaitingInput(inp.getOwner(), false, false);
        }
        updateObservers();
    }

    public final void clearInputs() {
        netLog.trace("clearInputs() called, stack size = {}", inputStack.size());
        int count = 0;
        PlayerView owner = null;
        while(!inputStack.isEmpty()) {
            InputSynchronized inp = inputStack.pop();
            owner = inp.getOwner();
            netLog.trace("Stopping input #{}: {}", count, inp.getClass().getSimpleName());
            inp.stop();
            count++;
        }
        netLog.trace("clearInputs() done, stopped {} inputs", count);
        markAwaitingInput(owner, false, false);

        updateObservers();
    }

    public final Input getActualInput(final PlayerControllerHuman controller) {
        final Input topMost = inputStack.peek(); // incoming input to Control
        if (topMost != null && !gameView.isGameOver()) {
            return topMost;
        }
        return new InputLockUI(this, controller);
    } // getInput()

    // only for debug purposes
    public String printInputStack() {
        return inputStack.toString();
    }

    public void setInput(final InputSynchronized input) {
        //if (HostedMatch.getHumanCount() > 1) { //update current player if needed
            //HostedMatch.setCurrentPlayer(game.getPlayer(input.getOwner()));
        //}
        inputStack.push(input);
        markAwaitingInput(input.getOwner(), true, true);
        syncPoint();
        updateObservers();
    }

    /**
     * Stamps whether the game is blocked waiting on this queue's player, and on
     * a transition to awaiting fires GameEventAwaitingInput so net-play handlers
     * can flush the updated view to every client. Firing is restricted to
     * setInput, which runs on the game thread (showAndWait) — the delta flush in
     * RemoteClientGuiGame is game-thread-only.
     */
    private void markAwaitingInput(final PlayerView owner, final boolean awaiting, final boolean fireEvent) {
        if (owner == null || owner.getAwaitingInput() == awaiting) {
            return;
        }
        owner.setAwaitingInput(awaiting);
        if (fireEvent) {
            final Game game = gameView.getGame();
            if (game != null) {
                game.fireEvent(new GameEventAwaitingInput(owner, awaiting));
            }
        }
    }

    void syncPoint() {
        synchronized (this) {
            // acquire and release lock, so that actions from Game thread happen before EDT reads their results
        }
    }

    public void onGameOver(final boolean releaseAllInputs) {
        for (final InputSynchronized inp : inputStack) {
            inp.relaseLatchWhenGameIsOver();
            if (!releaseAllInputs) {
                break;
            }
        }
    }
}
