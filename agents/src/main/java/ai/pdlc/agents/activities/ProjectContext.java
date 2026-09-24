package ai.pdlc.agents.activities;

import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders a project's Admin-editable brief/Confluence link/reference docs as a Mustache view,
 * injected into every reasoning prompt (grill/PO/plan/mention) as "Project context" — the one
 * place product/domain knowledge outside the story itself reaches an agent. */
public final class ProjectContext {

    private ProjectContext() {
    }

    public static Map<String, Object> view(Profile project) {
        ProjectMeta meta = project.project();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", meta.name());
        String brief = meta.brief() == null ? "" : meta.brief();
        view.put("brief", brief);
        view.put("hasBrief", !brief.isBlank());
        String confluenceUrl = meta.confluenceUrl() == null ? "" : meta.confluenceUrl();
        view.put("confluenceUrl", confluenceUrl);
        view.put("hasConfluence", !confluenceUrl.isBlank());
        List<Map<String, String>> docs = new ArrayList<>();
        for (ProjectMeta.DocLink doc : meta.docs()) {
            docs.add(Map.of("title", doc.title() == null ? "" : doc.title(), "url", doc.url() == null ? "" : doc.url()));
        }
        view.put("docs", docs);
        view.put("hasDocs", !docs.isEmpty());
        return view;
    }
}
