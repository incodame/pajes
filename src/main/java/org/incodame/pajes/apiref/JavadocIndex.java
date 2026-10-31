package org.incodame.pajes.apiref;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

final class JavadocIndex implements AutoCloseable {

    static final class ExecutableDocumentation {
        private final String comment;
        private final List<ParameterDocumentation> parameters;

        private ExecutableDocumentation(String comment, List<ParameterDocumentation> parameters) {
            this.comment = comment;
            this.parameters = List.copyOf(parameters);
        }

        String getComment() {
            return comment;
        }

        List<ParameterDocumentation> getParameters() {
            return parameters;
        }
    }

    static final class ParameterDocumentation {
        private final String name;
        private final String comment;

        private ParameterDocumentation(String name, String comment) {
            this.name = name;
            this.comment = comment;
        }

        String getName() {
            return name;
        }

        String getComment() {
            return comment;
        }
    }

    private final ZipFile archive;
    private final Map<String, ZipEntry> htmlEntries = new LinkedHashMap<>();

    private JavadocIndex(ZipFile archive) throws IOException {
        this.archive = archive;
        if (archive != null) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".html")) {
                    String typeName = entry.getName().substring(0, entry.getName().length() - ".html".length())
                            .replace('/', '.');
                    htmlEntries.put(typeName, entry);
                }
            }
        }
    }

    static JavadocIndex empty() throws IOException {
        return new JavadocIndex(null);
    }

    static JavadocIndex open(java.io.File sourceJar) throws IOException {
        return new JavadocIndex(new ZipFile(sourceJar));
    }

    ExecutableDocumentation find(String qualifiedTypeName, String memberName, List<String> parameterTypes) {
        ZipEntry entry = htmlEntries.get(qualifiedTypeName);
        if (entry == null) {
            entry = htmlEntries.get(qualifiedTypeName.replace('$', '.'));
        }
        if (entry == null) {
            return null;
        }

        Document document;
        try (InputStream input = archive.getInputStream(entry)) {
            document = Jsoup.parse(input, java.nio.charset.StandardCharsets.UTF_8.name(), "");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Javadoc entry " + entry.getName(), e);
        }
        String signature = memberName + "(" + String.join(",", parameterTypes) + ")";
        Element detail = findDetail(document, signature, memberName, parameterTypes);
        if (detail == null) {
            return null;
        }

        Element description = detail.selectFirst("div.block");
        String comment = description == null ? null : nonEmpty(description.text());
        return new ExecutableDocumentation(comment, findParameterDocumentation(detail));
    }

    private Element findDetail(Document document, String signature, String memberName, List<String> parameterTypes) {
        Element detail = document.getElementById(signature);
        if (detail != null) {
            return detail;
        }

        String legacyAnchor = memberName + "-" + String.join("-", parameterTypes) + "-";
        Elements anchors = document.select("[name], [id]");
        for (Element anchor : anchors) {
            if (legacyAnchor.equals(anchor.attr("name")) || legacyAnchor.equals(anchor.id())) {
                Element sibling = anchor;
                for (int attempt = 0; sibling != null && attempt < 4; attempt++) {
                    if ("section".equals(sibling.tagName()) || sibling.hasClass("blockList")) {
                        return sibling;
                    }
                    sibling = sibling.nextElementSibling();
                }
                Element parent = anchor.parent();
                while (parent != null && parent != document.body()) {
                    if ("section".equals(parent.tagName()) || parent.hasClass("blockList")) {
                        return parent;
                    }
                    parent = parent.parent();
                }
            }
        }
        return null;
    }

    private List<ParameterDocumentation> findParameterDocumentation(Element detail) {
        List<ParameterDocumentation> parameters = new ArrayList<>();
        for (Element label : detail.select("dt")) {
            if (!label.text().replace(":", "").trim().equalsIgnoreCase("Parameters")) {
                continue;
            }
            for (Element description = label.nextElementSibling();
                    description != null && "dd".equals(description.tagName());
                    description = description.nextElementSibling()) {
                Element nameElement = description.selectFirst("code");
                if (nameElement == null) {
                    continue;
                }
                String name = nameElement.text().trim();
                String text = description.text().trim();
                String comment = text.substring(name.length()).trim()
                        .replaceFirst("^[-\\u2013\\u2014:]\\s*", "");
                parameters.add(new ParameterDocumentation(name, nonEmpty(comment)));
            }
        }
        return parameters;
    }

    private String nonEmpty(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public void close() throws IOException {
        if (archive != null) {
            archive.close();
        }
    }
}
