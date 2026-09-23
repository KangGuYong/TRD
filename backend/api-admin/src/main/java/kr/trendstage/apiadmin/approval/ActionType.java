package kr.trendstage.apiadmin.approval;

/** approval_requests.action_type 값. DB 컬럼은 VARCHAR(40) — name()이 그대로 저장된다. */
public enum ActionType {
    PARAM_APPLY, VERDICT_REJUDGE, ITEM_VOID, LEDGER_ADJ, ACCOUNT_CREATE, ACCOUNT_ROLE_CHANGE
}
