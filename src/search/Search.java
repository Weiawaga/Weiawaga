package search;
import evaluation.*;
import movegen.*;

import java.util.Objects;

public class Search {
    public static int maxSearchDepth;
    public static boolean waitForStop = false;
    public final static int INF = 999999;

    private final static int NULL_MIN_DEPTH = 2;
    private final static int LMR_MIN_DEPTH = 2;
    private final static int LMR_MOVES_WO_REDUCTION = 1;
    private final static int ASPIRATION_WINDOW = 25;


    private static boolean stop;
    private static Move IDMove = null;
    private static int IDScore = 0;
    private static int selDepth;
    private static final int[][] LMR_TABLE = new int[64][64];
    static {
        // Ethereal LMR formula with depth and number of performed moves
        for (int depth = 1; depth < 64; depth++) {
            for (int moveNumber = 1; moveNumber < 64; moveNumber++) {
                LMR_TABLE[depth][moveNumber] = (int) (0.75f + Math.log(depth) * Math.log(moveNumber) / 2.25f);
            }
        }
    }

    public Search(){}

    public static void itDeep(Board board){
        Limits.calcTime(board.getSideToPlay(), board.gamePly());
        Limits.startTime = System.currentTimeMillis();
        IDMove = null;
        IDScore = 0;
        selDepth = 0;
        stop = false;
        int alpha = -INF;
        int beta = INF;
        int depth = 1;
        MoveOrder.ageHistory();

        // Deepen until end conditions
        while (depth <= maxSearchDepth || waitForStop) {

            // Check to see if the time has ended
            long elapsed = System.currentTimeMillis() - Limits.startTime;
            if (stop || elapsed >= Limits.timeAllocated / 2 || isScoreCheckmate(IDScore))
                break;


            negaMaxRoot(board, depth, alpha, beta);

            // Failed low, adjust window
            if (IDScore <= alpha) {
                alpha = -INF;
            }

            // Failed high, adjust window
            else if (IDScore >= beta){
                beta = INF;
            }

            // Adjust the window around the new score and increase the depth
            else {
                printInfo(board, depth);
                alpha = IDScore - ASPIRATION_WINDOW;
                beta = IDScore + ASPIRATION_WINDOW;
                depth++;
                Statistics.reset();
            }
        }
    }

    public static void negaMaxRoot(Board board, int depth, int alpha, int beta){
        int value = -INF;
        MoveList moves = board.generateLegalMoves();
        boolean inCheck = board.checkers() != 0;
        if (inCheck) ++depth;
        if (moves.size() == 1) {
            IDMove = moves.get(0);
            IDScore = 0;
            return;
        }

        MoveOrder.scoreMoves(board, moves, 0);
        Move bestMove = null;
        for (int i = 0; i < moves.size(); i++){
            MoveOrder.sortNextBestMove(moves, i);
            Move move = moves.get(i);

            board.push(move);
            value = -negaMax(board, depth - 1, 1, -beta, -alpha, true);
            board.pop();

            if (stop || Limits.checkLimits()) {
                stop = true;
                break;
            }
            if (value > alpha){
                bestMove = move;
                if (value >= beta){
                    TranspTable.set(board.hash(), beta, depth, TTEntry.LOWER_BOUND, bestMove);
                    IDMove = bestMove;
                    IDScore = beta;
                    return;
                }
                alpha = value;
                TranspTable.set(board.hash(), alpha, depth, TTEntry.UPPER_BOUND, bestMove);
            }
        }
        if (bestMove == null)
            bestMove = moves.get(0);

        if (!stop) {
            TranspTable.set(board.hash(), alpha, depth, TTEntry.EXACT, bestMove);
            IDMove = bestMove;
            IDScore = alpha;
        }
    }

    public static int negaMax(Board board, int depth, int ply, int alpha, int beta, boolean canApplyNull){
        int mateValue = INF - ply;
        boolean inCheck;
        int ttFlag = TTEntry.UPPER_BOUND;
        int reducedDepth;

        if (stop || Limits.checkLimits()) {
            stop = true;
            return 0;
        }

        // MATE DISTANCE PRUNING
        if (alpha < -mateValue) alpha = -mateValue;
        if (beta > mateValue - 1) beta = mateValue - 1;
        if (alpha >= beta) {
            Statistics.leafs++;
            return alpha;
        }

        inCheck = board.kingAttacked();
        if (depth <= 0 && !inCheck) return qSearch(board, depth, ply, alpha, beta);
        Statistics.nodes++;

        if (board.isRepetitionOrFifty()) {
            Statistics.leafs++;
            return 0;
        }

        // PROBE TTABLE
        final TTEntry ttEntry = TranspTable.probe(board.hash());
        if (ttEntry != null && ttEntry.depth() >= depth) {
            Statistics.ttHits++;
            switch (ttEntry.flag()) {
                case TTEntry.EXACT:
                    Statistics.leafs++;
                    return ttEntry.score();
                case TTEntry.LOWER_BOUND:
                    alpha = Math.max(alpha, ttEntry.score());
                    break;
                case TTEntry.UPPER_BOUND:
                    beta = Math.min(beta, ttEntry.score());
                    break;
            }
            if (alpha >= beta) {
                Statistics.leafs++;
                return ttEntry.score();
            }
        }

        // NULL MOVE
        if (canApplyNullWindow(board, depth, beta, inCheck, canApplyNull)) {
            int R = depth > 6 ? 3 : 2;
            board.pushNull();
            int value = -negaMax(board, depth - R - 1, ply, -beta, -beta + 1, false);
            board.popNull();
            if (stop) return 0;
            if (value >= beta){
                Statistics.betaCutoffs++;
                return beta;
            }
        }

        MoveList moves = board.generateLegalMoves();
        int value;
        Move bestMove = Move.nullMove;
        MoveOrder.scoreMoves(board, moves, ply);
        for (int i = 0; i < moves.size(); i++){
            MoveOrder.sortNextBestMove(moves, i);
            Move move = moves.get(i);

            // LATE MOVE REDUCTION
            reducedDepth = depth;
            if (canApplyLMR(depth, move, i)) {
                reducedDepth -= LMR_TABLE[Math.min(depth, 63)][Math.min(i, 63)];
            }

            if (inCheck) reducedDepth++;

            board.push(move);
            value = -negaMax(board, reducedDepth - 1, ply + 1, -beta, -alpha, true);
            board.pop();

            if (stop) return 0;

            if (value > alpha){
                bestMove = move;
                if (value >= beta) {
                    if (move.flags() == Move.QUIET) {
                        MoveOrder.addKiller(board, move, ply);
                        MoveOrder.addHistory(move, depth);
                    }
                    Statistics.betaCutoffs++;
                    ttFlag = TTEntry.LOWER_BOUND;
                    alpha = beta;
                    break;
                }
                ttFlag = TTEntry.EXACT;
                alpha = value;
            }
        }

        // Check if we are in checkmate or stalemate.
        if (moves.size() == 0){
            if (inCheck)
                alpha = -mateValue;
            else
                alpha = 0;
        }

        if (!bestMove.equals(Move.nullMove) && !stop) TranspTable.set(board.hash(), alpha, depth, ttFlag, bestMove);

        return alpha;
    }

    public static int qSearch(Board board, int depth, int ply, int alpha, int beta){
        if (stop || Limits.checkLimits()){
            stop = true;
            return 0;
        }
        selDepth = Math.max(ply, selDepth);
        Statistics.qnodes++;

        int value = Evaluation.evaluateState(board);

        if (value >= beta){
            Statistics.qleafs++;
            return beta;
        }

        if (alpha < value)
            alpha = value;

        MoveList moves = board.generateLegalQMoves();
        MoveOrder.scoreMoves(board, moves, ply);
        for (int i = 0; i < moves.size(); i++) {
            MoveOrder.sortNextBestMove(moves, i);
            Move move = moves.get(i);

            // Skip if underpromotion.
            if (move.isPromotion() && move.flags() != Move.PR_QUEEN && move.flags() != Move.PC_QUEEN)
                continue;

            board.push(move);
            value = -qSearch(board, depth - 1, ply + 1, -beta, -alpha);
            board.pop();

            if (stop)
                return 0;

            if (value > alpha) {
                if (value >= beta) {
                    Statistics.qbetaCutoffs++;
                    return beta;
                }
                alpha = value;
            }
        }
        return alpha;
    }

    public static boolean isScoreCheckmate(int score){
        return Math.abs(score) >= INF/2;
    }

    public static boolean canApplyNullWindow(Board board, int depth, int beta, boolean inCheck, boolean canApplyNull){
        return canApplyNull &&
                !inCheck &&
                depth >= NULL_MIN_DEPTH &&
                board.hasNonPawnMaterial(board.getSideToPlay()) &&
                Evaluation.evaluateState(board) >= beta;
    }

    public static boolean canApplyLMR(int depth, Move move, int moveIndex){
        return depth > LMR_MIN_DEPTH &&
                moveIndex > LMR_MOVES_WO_REDUCTION &&
                move.flags() == Move.QUIET;
    }

    public static String getPv(Board board, int depth){
        Move bestMove;
        if (TranspTable.probe(board.hash()) == null || depth == 0)
            return "";
        else
            bestMove = TranspTable.probe(board.hash()).move();
        board.push(bestMove);
        String pV = bestMove.uci() + " " + getPv(board, depth - 1);
        board.pop();
        return pV;
    }

    public static Move getMove(){
        return Objects.requireNonNullElseGet(IDMove, Move::nullMove);
    }

    public static int getScore(){
        return IDScore;
    }

    public static void stop(){
        stop = true;
    }

    public static void printInfo(Board board, int depth){
        System.out.print("info");
        System.out.print(" currmove " + IDMove.uci());
        System.out.print(" depth " + depth);
        System.out.print(" seldepth " + selDepth);
        System.out.print(" time " + Limits.timeElapsed());
        System.out.print(" score cp " + IDScore);
        System.out.print(" nodes " + Statistics.totalNodes());
        System.out.printf(" nps %.0f", Statistics.totalNodes()/((float)Limits.timeElapsed()/1000.0));
        System.out.println(" pv " + getPv(board, depth));
    }
}