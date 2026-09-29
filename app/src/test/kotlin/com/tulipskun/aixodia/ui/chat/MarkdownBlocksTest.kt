package com.tulipskun.aixodia.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The answer contract, exercised on the text the daemon actually stored. The
 * strings come from a real turn in the app's own database, so a renderer that
 * mangles them is caught here rather than on the phone.
 */
class MarkdownBlocksTest {

    private val orderedAnswer = "I have two available tools:\n\n" +
        "1. **read** - Read UTF-8 text files inside the workspace\n" +
        "2. **bash** - Run shell commands in a persistent session"

    @Test
    fun `an ordered list keeps every item exactly once`() {
        val parsed = blocks(orderedAnswer)
        val paragraph = parsed.filterIsInstance<Block.Paragraph>().single()
        assertEquals("I have two available tools:", paragraph.text)

        val list = parsed.filterIsInstance<Block.ItemList>().single()
        assertTrue(list.ordered)
        assertEquals(2, list.items.size)
        assertEquals("**read** - Read UTF-8 text files inside the workspace", list.items[0])
        assertEquals("**bash** - Run shell commands in a persistent session", list.items[1])
        // The failure this pins: one item that says the same thing twice.
        for (item in list.items) {
            val firstLine = item.substringBefore(" - ")
            assertEquals(1, Regex(Regex.escape(firstLine)).findAll(item).count())
        }
    }

    @Test
    fun `a list is not glued onto the paragraph above it`() {
        val parsed = blocks(orderedAnswer)
        val paragraph = parsed.filterIsInstance<Block.Paragraph>().single()
        assertTrue(paragraph.text.none { it == '1' })
    }

    @Test
    fun `a hard-wrapped list item continues instead of starting a new one`() {
        val parsed = blocks("1. read a file\n   that spans two lines")
        val list = parsed.filterIsInstance<Block.ItemList>().single()
        assertEquals(1, list.items.size)
        assertEquals("read a file that spans two lines", list.items.single())
    }

    @Test
    fun `bullets and ordered items do not merge into one list`() {
        val parsed = blocks("- one\n- two\n\n1. first\n2. second")
        val lists = parsed.filterIsInstance<Block.ItemList>()
        assertEquals(2, lists.size)
        assertEquals(listOf("one", "two"), lists[0].items)
        assertEquals(listOf("first", "second"), lists[1].items)
        assertTrue(!lists[0].ordered)
        assertTrue(lists[1].ordered)
    }

    @Test
    fun `headings, quotes and rules become their own blocks`() {
        val parsed = blocks("# Title\n\n> quoted\n\n---\n\nbody")
        assertEquals("Title", (parsed[0] as Block.Heading).text)
        assertEquals("quoted", (parsed[1] as Block.Quote).text)
        assertTrue(parsed[2] is Block.Rule)
        assertEquals("body", (parsed[3] as Block.Paragraph).text)
    }

    @Test
    fun `a fence keeps its body and its language`() {
        val parsed = blocks("before\n\n```go\ngo test ./...\n```\n\nafter")
        val code = parsed.filterIsInstance<Block.Code>().single()
        assertEquals("go", code.language)
        assertEquals("go test ./...", code.text)
    }

    @Test
    fun `an unfinished stream still parses`() {
        // A turn in flight: half a fence, an unpaired bold marker, a lone digit.
        for (partial in listOf("**bo", "1. rea", "```go\ngo bui", "# ", "> ")) {
            blocks(partial) // must not throw
        }
    }

    /**
     * The exact shape that reached the screen: one item reading the same thing
     * twice, with the second item of the list gone. A matched line used to be
     * handled and then handled again by the continuation branch below it.
     */
    @Test
    fun `a matched line is never also treated as a continuation`() {
        val list = blocks(orderedAnswer).filterIsInstance<Block.ItemList>().single()
        assertEquals(2, list.items.size)
        // Each line becomes its own item, verbatim, with the "1."/"2." consumed
        // as the list marker rather than left inside the text.
        assertEquals(
            listOf(
                "**read** - Read UTF-8 text files inside the workspace",
                "**bash** - Run shell commands in a persistent session",
            ),
            list.items,
        )
        // Nothing is dropped either: both sentences survive exactly once.
        val all = blocks(orderedAnswer).joinToString(" ") { b ->
            when (b) {
                is Block.ItemList -> b.items.joinToString(" ")
                is Block.Paragraph -> b.text
                is Block.Heading -> b.text
                is Block.Quote -> b.text
                is Block.Code -> b.text
                is Block.Rule -> ""
            }
        }
        assertEquals(1, Regex("Read UTF-8 text files").findAll(all).count())
        assertEquals(1, Regex("Run shell commands").findAll(all).count())
    }

    @Test
    fun `an answer with no markdown at all is one paragraph`() {
        val parsed = blocks("Hi there! 👋 How can I help you today?")
        assertEquals(1, parsed.size)
        assertTrue(parsed.single() is Block.Paragraph)
    }
}
