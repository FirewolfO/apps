package com.firewolf.friendsspeaking;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class PdfTranscriptParserTest {
    @Test public void joinsSentencesAcrossPageBreaks() {
        List<String> lines = PdfTranscriptParser.extractDialogue("2.正文\n你好\nHello\n2\f"
                + "2.正文\nthere.\n再见\nGoodbye.\n3\f3.四级词汇\nword");
        assertEquals(2, lines.size());
        assertEquals("Hello there.\n你好", lines.get(0));
    }

    @Test public void removesOnlyACompleteRepeatedTranscript() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 12; i++) body.append("台词").append(i).append("\nLine number ").append(i).append(".\n");
        List<String> lines = PdfTranscriptParser.extractDialogue("2.正文\n" + body + body + "\f3.四级词汇\nword");
        assertEquals(12, lines.size());
        List<String> conversation = PdfTranscriptParser.extractDialogue("2.正文\n是\nYes.\n是\nYes.\n结束\nDone.");
        assertEquals(3, conversation.size());
    }

    @Test public void keepsFinalLineWhenSecondPrintedCopyIsTruncated() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 12; i++) body.append("台词").append(i).append("\nLine ").append(i).append(".\n");
        String full = body + "最后一句\nFinal line!\n";
        assertEquals(13, PdfTranscriptParser.extractDialogue("2.正文\n" + full + body + "\f3.四级词汇").size());
        assertEquals(13, PdfTranscriptParser.extractDialogue("2.正文\n" + full + body
                + "最后一句\f3.四级词汇").size());
        List<String> corruptedTail = PdfTranscriptParser.extractDialogue("2.正文\n" + full + body
                + "最后一句\nFinahll ihtntpes!\f3.四级词汇");
        assertEquals(13, corruptedTail.size());
        assertEquals("Final line!\n最后一句", corruptedTail.get(12));
        assertEquals(13, PdfTranscriptParser.extractDialogue("2.正文\n" + full + body
                + "最h后一t句\nFinahll ihtntpes!\f3.四级词汇").size());
    }

    @Test public void reducesSixPrintedCopiesWithACorruptFinalFooter() {
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 12; i++) prefix.append("台词").append(i).append("\nLine ").append(i).append(".\n");
        String full = prefix + "最后一句\nFinal line!\n";
        String sixCopies = full + full + full + full + full + prefix + "最h后一t句\nFinahll ihtntpes!";
        List<String> lines = PdfTranscriptParser.extractDialogue("2.正文\n" + sixCopies + "\f3.四级词汇");
        assertEquals(13, lines.size());
        assertEquals("Final line!\n最后一句", lines.get(12));
    }

    @Test
    public void contentsPageDoesNotStartOrEndDialogue() {
        String pages = "Friends\nS01E01\f目录\n1. 内容概述\n2. 正文\n3. 四级词汇\n4. 六级词汇\f"
                + "1. 内容概述\nThis is a synopsis.\n剧情简介\f"
                + "\n2. 正文\n你好\nHello there.\n2\f"
                + "2.正文\n再见\nGoodbye.\n3\f"
                + "3.四级词汇\nexample\n例子\f4.六级词汇\nadvanced\n进阶";
        List<String> result = PdfTranscriptParser.extractDialogue(pages);
        assertEquals(2, result.size());
        assertEquals("Hello there.\n你好", result.get(0));
        assertEquals("Goodbye.\n再见", result.get(1));
    }

    @Test
    public void extractsOnlyBilingualDialogueSection() {
        String pages = "1. 内容概述\n简介\f"
                + "2. 正文\n你好\nHow are you?\n我很好\nI'm fine.\n4\f"
                + "2.正文\n回头见\nSee you later.\nhttps://www.zhihu.com/people/example\n5\f"
                + "3. 四级词汇\nexample\n例子";

        List<String> result = PdfTranscriptParser.extractDialogue(pages);

        assertEquals(3, result.size());
        assertEquals("How are you?\n你好", result.get(0));
        assertEquals("I'm fine.\n我很好", result.get(1));
        assertEquals("See you later.\n回头见", result.get(2));
    }
}
