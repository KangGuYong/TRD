import React, { createContext, useContext, useMemo, useState } from "react";
import type { Role } from "../theme";

/** RBAC 데모용 역할 전환(관리자 콘솔.dc.html). 실제 권한은 서버가 강제 — 여기선 UX 게이트. */
const RoleContext = createContext<{ role: Role; setRole: (r: Role) => void; locked: boolean }>({
  role: "OPERATOR",
  setRole: () => {},
  locked: false,
});

export function RoleProvider({ children, initialRole = "OPERATOR", locked = false }: {
  children: React.ReactNode; initialRole?: Role; locked?: boolean;
}) {
  const [role, setRoleState] = useState<Role>(initialRole);
  // locked(실제 로그인 모드)에서는 서버가 준 역할이 유일한 진실 — 클라이언트가 임의로 바꾸지 못한다.
  const setRole = locked ? () => {} : setRoleState;
  const value = useMemo(() => ({ role, setRole, locked }), [role, locked]);
  return <RoleContext.Provider value={value}>{children}</RoleContext.Provider>;
}
export const useRole = () => useContext(RoleContext);

/** 권한 매트릭스(02 §1.1)의 일부 — 액션별 허용 역할. */
export const CAN = {
  void: (r: Role) => r === "OPERATOR" || r === "ADMIN",
  merge: (r: Role) => r === "REVIEWER" || r === "OPERATOR" || r === "ADMIN",
  paramDraft: (r: Role) => r === "OPERATOR" || r === "ADMIN",
  seedRegister: (r: Role) => r === "OPERATOR" || r === "ADMIN",
  paramApply: (r: Role) => r === "ADMIN",
  ledgerAdj: (r: Role) => r === "ADMIN",
  approvalConfirm: (r: Role) => r === "ADMIN",
  reportTriage: (r: Role) => r === "REVIEWER" || r === "OPERATOR" || r === "ADMIN",
  reportDecide: (r: Role) => r === "OPERATOR" || r === "ADMIN",
  sanctionRequest: (r: Role) => r === "OPERATOR" || r === "ADMIN",
  accountView: (r: Role) => r === "ADMIN" || r === "AUDITOR",
  accountManage: (r: Role) => r === "ADMIN",
};
