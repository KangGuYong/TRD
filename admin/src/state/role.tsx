import React, { createContext, useContext, useMemo, useState } from "react";
import type { Role } from "../theme";

/** RBAC 데모용 역할 전환(관리자 콘솔.dc.html). 실제 권한은 서버가 강제 — 여기선 UX 게이트. */
const RoleContext = createContext<{ role: Role; setRole: (r: Role) => void }>({
  role: "OPERATOR",
  setRole: () => {},
});

export function RoleProvider({ children }: { children: React.ReactNode }) {
  const [role, setRole] = useState<Role>("OPERATOR");
  const value = useMemo(() => ({ role, setRole }), [role]);
  return <RoleContext.Provider value={value}>{children}</RoleContext.Provider>;
}
export const useRole = () => useContext(RoleContext);

/** 권한 매트릭스(02 §1.1)의 일부 — 액션별 허용 역할. */
export const CAN = {
  void: (r: Role) => r === "OPERATOR" || r === "ADMIN",
  merge: (r: Role) => r === "REVIEWER" || r === "OPERATOR" || r === "ADMIN",
  paramDraft: (r: Role) => r === "OPERATOR" || r === "ADMIN",
  paramApply: (r: Role) => r === "ADMIN",
  ledgerAdj: (r: Role) => r === "ADMIN",
  sanctionRequest: (r: Role) => r === "OPERATOR" || r === "ADMIN",
};
