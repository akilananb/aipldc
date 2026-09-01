// Pilot gate roles. Known simplification: hard-coded client-side to match
// infra/pdlc.yaml's gates.{G1,G2,G3}.roles (the REST contract does not expose
// the role list). If pdlc.yaml gate roles change, only this file needs editing.
export const GATE_ROLES: Record<'G1' | 'G2' | 'G3', string[]> = {
  G1: ['PO', 'SquadLead'],
  G2: ['FSDeveloper', 'QA'],
  G3: ['PO', 'SquadLead', 'QA'],
};
