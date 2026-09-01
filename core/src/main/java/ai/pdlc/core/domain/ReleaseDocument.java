package ai.pdlc.core.domain;

/**
 * One document in the release pack — {@code docs/agent-playbook.md} §7 "Release pack" table.
 * Status is derived by the workflow from its own {@code documentSignatures} map (whether — and by
 * whom — {@code id} has been signed), not carried here; this record is pure drafted content.
 *
 * @param id          stable document id, e.g. {@code change-notes}
 * @param title       human title, e.g. {@code Change notes}
 * @param content     rendered markdown
 * @param checkerRole the ONE role that must sign this specific document, e.g. {@code PO}
 */
public record ReleaseDocument(String id, String title, String content, String checkerRole) {
}
