package kr.trendstage.apipublic.web;

/** 관측이 끝난 항목(422, Problem.type = item-closed) — 마감 이후·JUDGING·RESOLVED·VOID·MERGED(J6). */
public class ItemClosedException extends RuntimeException {
    public ItemClosedException(String message) {
        super(message);
    }
}
