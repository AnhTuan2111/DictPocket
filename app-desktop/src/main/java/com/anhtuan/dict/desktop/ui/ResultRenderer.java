package com.anhtuan.dict.desktop.ui;

import com.anhtuan.dict.core.model.Candidate;
import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Example;
import com.anhtuan.dict.core.model.Idiom;
import com.anhtuan.dict.core.model.Segment;
import com.anhtuan.dict.core.model.SegmentKind;
import com.anhtuan.dict.core.model.Sense;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.service.ReverseSearchService;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// Biến kết quả của dict-core thành Node JavaFX. Không dùng WebView: javafx-web kéo theo cả
// WebKit (+35 MB đĩa, +40 MB RAM) chỉ để hiện văn bản có định dạng.
// Mục từ vẽ bằng TextFlow, kết quả dịch câu vẽ bằng các "chip" trong FlowPane.
final class ResultRenderer
{
    private ResultRenderer()
    {
    }

    // ------------------------------------------------------------------ tra từ

    static Node renderEntries(List<Entry> entries)
    {
        return renderEntries(entries, null);
    }

    // Vẽ mục từ, có thể kèm một cụm được đưa lên đầu. "give up" không phải mục từ riêng mà là
    // dòng "to give up" nằm giữa mục "give" dài 15 nghĩa: đưa cụm khớp lên trước, mục cha giữ
    // nguyên bên dưới, để người dùng khỏi phải tự đi tìm.
    static Node renderEntries(List<Entry> entries, String focusPhrase)
    {
        VBox outer = new VBox(6);
        List<Idiom> focus = focusPhrase == null ? List.of() : findIdioms(entries, focusPhrase);
        if (!focus.isEmpty())
        {
            VBox card = new VBox(2);
            card.getStyleClass().add("phrase-card");
            for (Idiom idiom : focus)
            {
                card.getChildren().add(renderIdiom(idiom));
            }
            outer.getChildren().add(card);
            outer.getChildren().add(styled("Nằm trong mục từ:", "message"));
        }
        outer.getChildren().add(renderEntryList(entries));
        return outer;
    }

    private static List<Idiom> findIdioms(List<Entry> entries, String normPhrase)
    {
        List<Idiom> out = new ArrayList<>(2);
        for (Entry e : entries)
        {
            for (Idiom i : e.idioms())
            {
                String norm = TextNormalizer.normalizeHeadword(i.phrase());
                if (norm.equals(normPhrase) || norm.equals("to " + normPhrase))
                {
                    out.add(i);
                }
            }
        }
        return out;
    }

    private static Node renderEntryList(List<Entry> entries)
    {
        VBox box = new VBox(2);
        box.getStyleClass().add("result-box");
        for (int i = 0; i < entries.size(); i++)
        {
            Entry e = entries.get(i);
            if (i > 0)
            {
                Label hr = new Label("(mục từ đồng âm " + (i + 1) + ")");
                hr.getStyleClass().add("homograph-note");
                box.getChildren().add(hr);
            }
            box.getChildren().add(headline(e));
            for (Sense s : e.senses())
            {
                box.getChildren().add(renderSense(s));
            }
            if (!e.idioms().isEmpty())
            {
                Label title = new Label("Thành ngữ / cụm từ");
                title.getStyleClass().add("section-title");
                box.getChildren().add(title);
                for (Idiom idiom : e.idioms())
                {
                    box.getChildren().add(renderIdiom(idiom));
                }
            }
            for (String ref : e.crossRefs())
            {
                box.getChildren().add(styled("→ " + ref, "cross-ref"));
            }
        }
        return box;
    }

    private static Node headline(Entry e)
    {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("headline");
        Text head = new Text(e.headword());
        head.getStyleClass().add("headword");
        flow.getChildren().add(head);
        if (e.ipa() != null)
        {
            Text ipa = new Text("  /" + e.ipa() + "/");
            ipa.getStyleClass().add("ipa");
            flow.getChildren().add(ipa);
        }
        if (e.variant() != null)
        {
            Text variant = new Text("   (còn viết: " + e.variant() + ")");
            variant.getStyleClass().add("variant");
            flow.getChildren().add(variant);
        }
        return flow;
    }

    private static Node renderSense(Sense s)
    {
        VBox box = new VBox(1);
        box.getStyleClass().add("sense");
        if (s.pos() != null)
        {
            box.getChildren().add(styled(s.pos(), "pos"));
        }
        List<String> glosses = s.glosses();
        for (int i = 0; i < glosses.size(); i++)
        {
            box.getChildren().add(styled((i + 1) + ". " + glosses.get(i), "gloss"));
        }
        for (Example ex : s.examples())
        {
            box.getChildren().add(renderExample(ex));
        }
        return box;
    }

    private static Node renderIdiom(Idiom idiom)
    {
        VBox box = new VBox(1);
        box.getStyleClass().add("idiom");
        box.getChildren().add(styled(idiom.phrase(), "idiom-phrase"));
        for (String gloss : idiom.glosses())
        {
            box.getChildren().add(styled("• " + gloss, "gloss"));
        }
        for (Example ex : idiom.examples())
        {
            box.getChildren().add(renderExample(ex));
        }
        return box;
    }

    private static Node renderExample(Example ex)
    {
        String text = ex.hasTranslation() ? ex.en() + "   —   " + ex.vi() : ex.en();
        return styled(text, "example");
    }

    // ------------------------------------------------------------------ dịch câu

    // Mỗi segment là một chip: từ gốc ở trên, nghĩa ở dưới. Chip nào có nhiều nghĩa thì bấm vào
    // ra menu đổi nghĩa: máy không biết chọn nghĩa nào nên để người đọc chọn.
    static Node renderGloss(List<Segment> segments)
    {
        FlowPane pane = new FlowPane(6, 8);
        pane.getStyleClass().add("gloss-pane");
        for (Segment s : segments)
        {
            if (s.kind() == SegmentKind.PUNCT)
            {
                if (!s.sourceText().isBlank())
                {
                    pane.getChildren().add(styled(s.sourceText(), "chip-punct"));
                }
                continue;
            }
            pane.getChildren().add(chip(s));
        }
        return pane;
    }

    private static Node chip(Segment s)
    {
        VBox chip = new VBox(1);
        chip.getStyleClass().addAll("chip", switch (s.kind())
        {
            case PHRASE -> "chip-phrase";
            case UNKNOWN -> "chip-unknown";
            default -> "chip-word";
        });

        Label source = new Label(s.sourceText());
        source.getStyleClass().add("chip-source");

        String gloss = s.displayGloss();
        Label meaning = new Label(gloss == null ? "(không có trong từ điển)" : gloss);
        meaning.getStyleClass().add("chip-gloss");
        meaning.setWrapText(false);

        chip.getChildren().addAll(source, meaning);

        if (s.candidates().size() > 1)
        {
            Label more = new Label(s.candidates().size() - 1 + " nghĩa khác ▾");
            more.getStyleClass().add("chip-more");
            chip.getChildren().add(more);

            ContextMenu menu = new ContextMenu();
            for (Candidate c : s.candidates())
            {
                String label = c.pos() == null ? c.gloss() : c.gloss() + "   [" + c.pos() + "]";
                MenuItem item = new MenuItem(label);
                item.setOnAction(ev -> meaning.setText(c.gloss()));
                menu.getItems().add(item);
            }
            chip.setOnMouseClicked(ev ->
            {
                if (ev.getButton() == MouseButton.PRIMARY)
                {
                    menu.show(chip, Side.BOTTOM, 0, 0);
                }
            });
        }
        return chip;
    }

    // ------------------------------------------------------------------ Việt → Anh

    static Node renderHits(List<ReverseSearchService.Hit> hits, Consumer<String> onOpen)
    {
        VBox box = new VBox();
        box.getStyleClass().add("result-box");
        int rank = 1;
        for (ReverseSearchService.Hit h : hits)
        {
            VBox row = new VBox(1);
            row.getStyleClass().add("hit");

            TextFlow flow = new TextFlow();
            Text rankText = new Text(rank++ + ". ");
            rankText.getStyleClass().add("hit-rank");
            Text word = new Text(h.display());
            word.getStyleClass().add("hit-word");
            flow.getChildren().addAll(rankText, word);
            if (h.entry().ipa() != null)
            {
                Text ipa = new Text("  /" + h.entry().ipa() + "/");
                ipa.getStyleClass().add("hit-ipa");
                flow.getChildren().add(ipa);
            }
            row.getChildren().add(flow);
            if (h.matchedGloss() != null)
            {
                row.getChildren().add(styled(h.matchedGloss(), "hit-gloss"));
            }

            String headword = h.entry().headword();
            row.setOnMouseClicked(ev -> onOpen.accept(headword));
            box.getChildren().add(row);
        }
        return box;
    }

    // ------------------------------------------------------------------ chung

    // Câu đã dịch, hiện to và nổi bật: đây là thứ người dùng tìm đến ở chế độ dịch câu
    static Node translation(String text)
    {
        Label label = new Label(text == null || text.isBlank() ? "(không dịch được)" : text);
        label.getStyleClass().add("translation");
        label.setWrapText(true);
        VBox box = new VBox(label);
        box.getStyleClass().add("translation-box");
        return box;
    }

    // Dòng "Ý bạn là ...?", bấm vào là tra luôn từ được gợi ý
    static Node suggestion(List<String> words, Consumer<String> onPick)
    {
        HBox row = new HBox(8);
        row.getStyleClass().add("suggestion-row");
        row.getChildren().add(styled("Ý bạn là:", "message"));
        for (String w : words)
        {
            Label link = new Label(w);
            link.getStyleClass().add("suggestion-link");
            link.setOnMouseClicked(e -> onPick.accept(w));
            row.getChildren().add(link);
        }
        return row;
    }

    static Node message(String text)
    {
        return styled(text, "message");
    }

    // Chú thích có icon, dùng dưới bản dịch để nhắc đây không phải bản dịch hoàn chỉnh
    static Node note(String text)
    {
        HBox row = new HBox(8, PixelIcons.small("info"), styled(text, "message"));
        row.getStyleClass().add("note");
        return row;
    }

    private static Label styled(String text, String styleClass)
    {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        return label;
    }
}
