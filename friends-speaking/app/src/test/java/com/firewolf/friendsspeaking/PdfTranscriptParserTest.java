package com.firewolf.friendsspeaking;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class PdfTranscriptParserTest {
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
