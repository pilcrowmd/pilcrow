# PilcrowMD test pack – Writers

**What this is.** A long piece of writing with the things writers reach for: callouts, footnotes,
tables, collapsible sections, links and quotes. The story is made up for this pack.

**What to look for**

- **Long reading.** Scroll through Part A. Lines should be comfortable to read, with even spacing
  and no jumps while you scroll. Try **Settings → Reading & code font** and **Preview text size**.
- **Footnotes.** Tap a raised number to jump to its note, and the arrow to jump back.
- **Callouts.** Five coloured boxes, each with its own icon and title.
- **Collapsible sections.** They start closed (unless marked `open`) and open with a tap.
- **Headings drawer.** It lists every chapter; tapping one jumps there.
- **Search.** Search for *harbour*: it appears many times, and stepping through the matches should
  land on each one.
- **Edit and save.** Switch to Edit, change a word, save, and reopen: the file must come back
  exactly as you left it, with nothing reflowed or reformatted.

**Known gaps in version 1.0.13** – we know about these, no need to report them:

- A picture in a table cell is dropped.
- A link to another note (`./notes.md`) does nothing when tapped.
- Callouts with a custom title, a fold marker (`> [!TIP]-`) or inside a list show as plain quotes
  with the marker visible.
- `!!! note` and `::: warning` boxes (from other Markdown tools) show as plain text.
- `==highlight==`, `~subscript~` and `^superscript^` show as typed.
- Emoji codes like `:smile:` stay as text (real emoji work).
- Definition lists (a term, then a line starting with `:`) are not styled.
- A heading with `{#my-id}` after it shows that text.
- Wiki links `[[Page]]` and review marks like `{++added++}` show as typed.
- Footnotes written inline (`^[note]`) are not supported. A footnote used twice links back to its
  first use only.
- In a right-to-left list, the bullet stays on the left.

Found something not on this list? Please open an issue at
<https://github.com/pilcrowmd/pilcrow/issues> and say which pack and which part.

---

## Contents

1. [Part A – The harbour keeper's year](#part-a--the-harbour-keepers-year)
2. [Part B – Callouts](#part-b--callouts)
3. [Part C – Footnotes](#part-c--footnotes)
4. [Part D – Tables](#part-d--tables)
5. [Part E – Collapsible sections](#part-e--collapsible-sections)
6. [Part F – Links, quotes and pictures](#part-f--links-quotes-and-pictures)
7. [Part G – Known gaps, side by side](#part-g--known-gaps-side-by-side)

(These links point to headings in this file. Tap one to jump to that part.)

## Part A – The harbour keeper's year

### Chapter one: January

The harbour at Gull Bay freezes at the edges in January, never in the middle. Mara Quill, who kept
the harbour log for thirty-one winters, liked to say that the sea was too proud to stop moving and
too tired to move much. Each morning she walked the length of the north wall with a notebook in one
mitten and a pencil tied to the other, writing down the height of the water, the colour of the sky
and the names of any boats that had come in during the night.

Most mornings there were none. The fishing fleet wintered in the south, and the ferry ran only on
Thursdays, when the weather allowed, which in January it seldom did. So the log filled up with
small observations instead: a seal asleep on the slipway, a crate of oranges washed up from nobody
knew where, the exact minute the street lamps went off along the front.

> Nothing that happens in a harbour is too small to write down, she told the new clerk. You never
> know which line someone will need in forty years.

### Chapter two: April

By April the boats came back, and with them the noise. The log changed character. Where it had
held one line a day, it now held thirty: arrivals and departures, catches weighed, a dispute over a
mooring settled with a handshake and a bag of mackerel. Mara wrote faster and slept less, and the
pencil tied to her mitten was replaced, for the season, by a proper pen.

She kept two columns that nobody else understood. The first recorded what the skippers said the
weather would do. The second, filled in a day later, recorded what it had actually done. Over the
years the second column proved the skippers right more often than the forecast, and the harbour
office quietly began to ask them first.

### Chapter three: August

August was for visitors. They photographed the lighthouse, ate chips on the harbour wall and asked
Mara whether the tide was coming in or going out, a question she answered with great patience and
a small chart she had drawn herself and laminated against the rain. On the busiest day of the year
she counted four hundred and twelve people on the north wall at noon, and one dog that refused to
leave the end of the jetty until a fisherman gave it a fish.

The log for that day runs to four pages. Most of it concerns a sailing dinghy that capsized just
inside the harbour mouth, the two children who swam ashore laughing, and the parents who did not
laugh at all.

### Chapter four: November

In November the harbour emptied again, and Mara read back through the year. She did this every
autumn, slowly, with a mug of tea going cold beside her, and she found each time that the small
lines mattered most: the seal on the slipway was there again in March, and again in October; the
oranges, it turned out, had come from a container ship that lost part of its cargo off the cape,
and someone from the shipping company wrote, months later, to ask whether anyone had kept a record.

Someone had.

## Part B – Callouts

> [!NOTE]
> The harbour log is kept in pencil in winter, because ink freezes.

> [!TIP]
> Write the time first and the event second. Times are easier to search for later.

> [!IMPORTANT]
> Every arrival is logged, even if the boat leaves again within the hour.

> [!WARNING]
> The north wall is slippery at low tide. Walk on the landward side.

> [!CAUTION]
> Never stand between a mooring line and the boat while it is being tied up.

A callout that holds more than one paragraph and a list:

> [!TIP]
> Keep a spare pencil in your coat.
>
> - one sharpened
> - one blunt, for wet paper

## Part C – Footnotes

The harbour log goes back to 1891.[^oldest] It has been kept without a break, except for six weeks in
1953, when the office flooded.[^flood] Mara's own entries begin in 1994.[^mara]

Some readers ask about the flood more than once.[^flood]

[^oldest]: The earliest volume is held in the town library and can be read on request.
[^flood]: The water reached the second shelf. The volumes on the first shelf were dried page by page
    in the bakery's ovens, at the lowest heat, over several nights.
[^mara]: She took over from her uncle, who had kept the log for twenty-two years before her.

## Part D – Tables

A simple table with alignment:

| Month     | Boats in | Boats out | Busiest day |
| :-------- | -------: | --------: | :---------: |
| January   |        3 |         2 |    14th     |
| April     |      214 |       198 |    27th     |
| August    |      388 |       391 |    15th     |
| November  |       41 |        57 |     2nd     |

A wide table, wider than the screen:

| Date       | Boat           | Skipper      | Arrived | Left  | Catch (kg) | Weather       | Wind | Swell | Notes                                   |
| ---------- | -------------- | ------------ | ------- | ----- | ---------: | ------------- | ---- | ----- | --------------------------------------- |
| 2026-04-03 | Morning Star   | J. Ferris    | 05:40   | 14:10 |        320 | clear         | W 3  | 0.5 m | first mackerel of the season            |
| 2026-04-03 | Little Auk     | P. Okonkwo   | 06:15   | 13:55 |        185 | clear         | W 3  | 0.5 m | net repaired at the quay                |
| 2026-04-04 | Hope Returning | S. Lindqvist | 07:02   | 16:30 |        410 | showers later | SW 4 | 1.0 m | delayed by a fouled propeller           |

A table with empty cells, a pipe character and Unicode:

| Name           | Symbol | Value |
| -------------- | :----: | ----: |
| Pipe in text   |  \|    |     1 |
| Empty cell     |        |       |
| Accents        |   é    |   2.5 |
| Emoji          |   ⚓   |     – |

## Part E – Collapsible sections

<details>
<summary>The full list of boats registered in Gull Bay</summary>

- Morning Star
- Little Auk
- Hope Returning
- The Patient Heron
- Second Thoughts

</details>

<details open>
<summary>This one starts open</summary>

It was written with `<details open>`, so it is already expanded. Tap the title to close it.

</details>

## Part F – Links, quotes and pictures

An ordinary link: [the PilcrowMD website](https://pilcrowmd.com). A link with a title:
[example page](https://example.org/ "An example page"). A reference-style link: [the town archive][archive].

[archive]: https://example.org/archive

An angle-bracket link: <https://example.org/log>. An email address: <archive@example.org>.

A quote with an attribution:

> The sea does not keep a diary. That is why we do.
>
> – the first page of the 1891 volume

A nested quote:

> The new clerk asked how long to keep the old logs.
>
> > For ever, said Mara.

A picture from the web (PilcrowMD never downloads pictures, so you will see a placeholder box):

![The north wall of Gull Bay harbour at low tide, with a seal on the slipway](https://example.org/gull-bay.png)

## Part G – Known gaps, side by side

Each line below is one of the known gaps listed at the top, so you can see what it looks like.

Formatting in a table cell:

| Style  | Example                              |
| ------ | ------------------------------------ |
| Bold   | **bold text**                        |
| Link   | [a link](https://example.org)        |

> [!NOTE] A custom title
> A callout with its own title.

> [!TIP]-
> A folded callout.

!!! note "A box from another Markdown tool"
    Its body is indented by four spaces.

::: warning
A fenced warning box from another tool.
:::

==Highlighted text==, H~2~O and x^2^.

Press <kbd>Ctrl</kbd> + <kbd>S</kbd> to save. Emoji code: :anchor: – real emoji: ⚓.

Harbour
: A sheltered place where boats can moor.

1) A list written with a bracket
2) The second item

### A heading with an id {#custom-id}

A wiki link: [[Harbour log]]. Review marks: {++added text++} {--removed text--}.

An inline footnote.^[This kind of footnote is not supported yet.]

Right to left:

- منارة الميناء
- סירת דייגים

---

**End of the Writers pack.** This file is public domain (CC0 1.0). Copy, change and share it freely.
