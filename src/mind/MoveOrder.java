package mind;

import movegen.*;
import java.util.Comparator;


public class MoveOrder {

    private static final int[][][] killerMoves = new int[2][1000][3];
    private static final int[][][] historyMoves = new int[2][64][64];
    private static final int[][] MvvLvaScores = new int[6][6];

    static {
        final int[] VictimScore = {100, 200, 300, 400, 500, 600};
        for (int attacker = PieceType.PAWN; attacker <= PieceType.KING; attacker++) {
            for (int victim = PieceType.PAWN; victim <= PieceType.KING; victim++) {
                MvvLvaScores[victim][attacker] = VictimScore[victim] + 6 - (VictimScore[attacker] / 100);
            }
        }
    }

    public static void addKiller(Board board, Move move, int ply){
        int side = board.getSideToPlay();
        for (int i = killerMoves[side][ply].length - 2; i >= 0; i--)
            killerMoves[side][ply][i+1] = killerMoves[side][ply][i];
        killerMoves[side][ply][0] = move.move();
    }

    public static boolean isKiller(Board board, Move move, int ply){
        int moveInt = move.move();
        int side = board.getSideToPlay();
        for (int i = 0; i < killerMoves[side][ply].length; i++){
            if (moveInt == killerMoves[side][ply][i])
                return true;
        }
        return false;
    }

    public static void clearKillers(){
        for (int color = Side.WHITE; color <= Side.BLACK; color++){
            for (int ply = 0; ply < killerMoves[0].length; ply++){
                for (int killer_i = 0; killer_i < killerMoves[0][0].length; killer_i++){
                    killerMoves[color][ply][killer_i] = 0;
                }
            }
        }
    }

    public static void addHistory(Board board, Move move, int depth){
        historyMoves[board.getSideToPlay()][move.from()][move.to()] += depth*depth;
    }

    public static int getHistoryValue(Board board, Move move){
        return historyMoves[board.getSideToPlay()][move.from()][move.to()];
    }

    public static void clearHistory(){
        for (int color = Side.WHITE; color <= Side.BLACK; color++){
            for (int sq1 = Square.A1; sq1 <= Square.H8; sq1++){
                for (int sq2 = Square.A1; sq2 <= Square.H8; sq2++){
                    historyMoves[color][sq1][sq2] = 0;
                }
            }
        }
    }

    public static int getMvvLvaScore(Board board, Move move){
        return MvvLvaScores[board.pieceTypeAt(move.to())][board.pieceTypeAt(move.from())];
    }
    
    public static MoveList moveOrdering(final Board board, final MoveList moves, int ply){
        MoveList sortedMoves = new MoveList();
        MoveList killers = new MoveList();
        MoveList promotions = new MoveList();
        MoveList captures = new MoveList();
        MoveList quiet = new MoveList();

        Move pvMove = Move.nullMove();
        TTEntry ttEntry = TranspTable.get(board.hash());
        if (ttEntry != null) {
            pvMove = ttEntry.move();
        }

        for (Move move : moves){
            if (move.equals(pvMove)) {
               sortedMoves.add(move);
            }
            else if(isKiller(board, move, ply)){
                killers.add(move);
            }
            else {
                switch (move.flags()) {
                    case Move.PC_BISHOP, Move.PC_KNIGHT, Move.PC_ROOK, Move.PC_QUEEN, Move.PR_BISHOP, Move.PR_KNIGHT, Move.PR_ROOK, Move.PR_QUEEN -> promotions.add(move);
                    case Move.CAPTURE -> captures.add(move);
                    case Move.QUIET, Move.EN_PASSANT, Move.DOUBLE_PUSH, Move.OO, Move.OOO -> quiet.add(move);
                }
            }
        }

        Comparator<Move> compareByMvvLva = (Move move1, Move move2) -> Integer.compare(getMvvLvaScore(board, move2), getMvvLvaScore(board, move1));
        Comparator<Move> compareByHistory = (Move move1, Move move2) -> Integer.compare(getHistoryValue(board, move2), getHistoryValue(board, move1));
        captures.sort(compareByMvvLva);
        quiet.sort(compareByHistory);
        sortedMoves.addAll(captures);
        sortedMoves.addAll(killers);
        sortedMoves.addAll(promotions);
        sortedMoves.addAll(quiet);
        return sortedMoves;
    }
}
