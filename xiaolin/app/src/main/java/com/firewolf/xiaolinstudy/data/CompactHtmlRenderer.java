package com.firewolf.xiaolinstudy.data;

import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogArticle;

import java.util.List;

public final class CompactHtmlRenderer {
    private CompactHtmlRenderer() {}

    public static String render(CatalogArticle article) {
        return render(article, false);
    }

    public static String render(CatalogArticle article, boolean darkMode) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html><html lang=\"zh-CN\"><head>")
                .append("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=5\">")
                .append("<meta name=\"color-scheme\" content=\"")
                .append(darkMode ? "dark" : "light").append("\">")
                .append("<title>").append(escape(article.getTitle())).append("</title>")
                .append("<style>")
                .append(darkMode
                        ? ":root{color-scheme:dark;--bg:#101412;--surface:#19201d;--ink:#eff4f1;--muted:#a9b5af;--brand:#48c79b;--brand-strong:#7ddebd;--divider:#34413b;--soft:#1f392f;--soft-border:#3d745c;--danger:#ff8b72;--danger-bg:#35231f;--info:#8fb2f4;--info-bg:#202c43}"
                        : ":root{color-scheme:light;--bg:#f6f7f5;--surface:#fff;--ink:#171a1c;--muted:#667078;--brand:#16765a;--brand-strong:#0d563f;--divider:#e3e7e4;--soft:#eaf5f0;--soft-border:#b3daca;--danger:#b93f2b;--danger-bg:#fff7f5;--info:#3665a6;--info-bg:#edf2ff}")
                .append("*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font-family:-apple-system,BlinkMacSystemFont,'Noto Sans SC','PingFang SC',sans-serif;line-height:1.7}")
                .append("main{max-width:780px;margin:auto;padding:18px 16px 40px}.hero,.card{background:var(--surface);border:1px solid var(--divider);border-radius:14px;padding:18px;margin-bottom:14px}")
                .append(".eyebrow{font-size:12px;font-weight:700;color:var(--brand);letter-spacing:.08em}.hero h1{font-size:24px;line-height:1.35;margin:7px 0 8px}.summary{margin:0;color:var(--muted)}")
                .append("h2{font-size:17px;margin:0 0 10px;color:var(--brand-strong)}.answer{font-size:16px;font-weight:600;margin:0}.label{display:inline-block;padding:3px 9px;border-radius:999px;background:var(--soft);color:var(--brand-strong);font-size:12px;font-weight:700;margin-bottom:10px}")
                .append("ul{padding-left:21px;margin:0}li+li{margin-top:7px}.pitfall{border-left:4px solid var(--danger);background:var(--danger-bg)}.pitfall h2{color:var(--danger)}")
                .append(".diagram{overflow:hidden}.flow{display:flex;align-items:stretch;gap:8px;overflow-x:auto;padding:4px 0 8px}.node{min-width:116px;max-width:170px;flex:1;padding:12px 10px;border-radius:10px;background:var(--soft);border:1px solid var(--soft-border);text-align:center;font-size:13px;font-weight:700;color:var(--brand-strong);white-space:pre-line}.arrow{display:flex;align-items:center;color:var(--brand);font-weight:900}")
                .append(".source{text-align:center;font-size:12px;color:var(--muted)}.source a{color:var(--brand);font-weight:700;text-decoration:none}.offline{display:inline-flex;align-items:center;gap:5px;background:var(--info-bg);color:var(--info);padding:4px 9px;border-radius:999px;font-size:11px;font-weight:700}")
                .append("@media(max-width:520px){main{padding:14px 12px 32px}.hero,.card{padding:16px;border-radius:12px}.hero h1{font-size:21px}.flow{flex-direction:column;overflow:visible}.node{max-width:none;width:100%}.arrow{justify-content:center;transform:rotate(90deg)}}")
                .append("</style></head><body><main>");

        html.append("<section class=\"hero\"><div class=\"eyebrow\">精简版 · 面试速记</div><h1>")
                .append(escape(article.getTitle())).append("</h1><p class=\"summary\">")
                .append(escape(article.getSummary())).append("</p></section>");
        html.append("<section class=\"card\"><span class=\"label\">30 秒回答</span><p class=\"answer\">")
                .append(escape(article.getAnswer())).append("</p></section>");

        appendDiagram(html, article);
        appendList(html, "核心要点", article.getKeyPoints());
        appendList(html, "面试官常追问", article.getFollowUps());
        if (!article.getPitfall().isEmpty()) {
            html.append("<section class=\"card pitfall\"><h2>易错提醒</h2><p>")
                    .append(escape(article.getPitfall())).append("</p></section>");
        }
        html.append("<p class=\"source\"><span class=\"offline\">离线可读</span>");
        if (!article.getSourceUrl().isEmpty()) {
            html.append("　需要展开细节时，<a href=\"").append(attribute(article.getSourceUrl()))
                    .append("\">查看小林原文</a>");
        }
        html.append("</p></main></body></html>");
        return html.toString();
    }

    private static void appendDiagram(StringBuilder html, CatalogArticle article) {
        List<String> nodes = article.getDiagramNodes();
        if (nodes.isEmpty()) return;
        html.append("<section class=\"card diagram\"><h2>")
                .append(escape(article.getDiagramTitle().isEmpty() ? "关键流程图" : article.getDiagramTitle()))
                .append("</h2><div class=\"flow\">");
        for (int index = 0; index < nodes.size(); index++) {
            if (index > 0) html.append("<div class=\"arrow\">→</div>");
            html.append("<div class=\"node\">").append(escape(nodes.get(index))).append("</div>");
        }
        html.append("</div></section>");
    }

    private static void appendList(StringBuilder html, String title, List<String> values) {
        if (values.isEmpty()) return;
        html.append("<section class=\"card\"><h2>").append(escape(title)).append("</h2><ul>");
        for (String value : values) html.append("<li>").append(escape(value)).append("</li>");
        html.append("</ul></section>");
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String attribute(String value) {
        return escape(value).replace("`", "&#96;");
    }
}
