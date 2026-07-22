---
title: LaTeX & Frontmatter Stress Test
author: Pilcrow Test Suite
version: 1.0
---

# LaTeX Math & Frontmatter Test Document

This document tests:
- YAML frontmatter rendering (above)
- Inline LaTeX math (`$...$`)
- Block LaTeX math (`$$...$$`)
- Currency notation (JLatexMath greedy behavior)
- Graceful fallback on malformed LaTeX

## Inline Math Examples

Einstein's mass-energy equivalence: $E = mc^2$.

The Pythagorean theorem: $a^2 + b^2 = c^2$.

A long inline formula: $\frac{\partial^2 u}{\partial t^2} = c^2 \nabla^2 u$ (wave equation).

Escaped currency (user workaround): \$5 to \$10 range price.

Unescaped currency (will be parsed as math): Price $5 to $10 or more.

## Block Math Examples

The quadratic formula:

$$
x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
$$

A matrix:

$$
\begin{pmatrix} 1 & 2 & 3 \\ 4 & 5 & 6 \\ 7 & 8 & 9 \end{pmatrix}
$$

An integral:

$$
\int_0^\infty e^{-x^2} dx = \frac{\sqrt{\pi}}{2}
$$

## Malformed LaTeX (Graceful Fallback)

This should fall back to raw text: $E = mc^2 INVALID SYNTAX$.

Unclosed delimiter: $E = mc^2 (no closing $).

## Mixed Content

Prose with inline math: The function $f(x) = \sin(x)$ oscillates between -1 and 1.

A list with math:
- Item 1: $x_1 = 5$
- Item 2: $x_2 = 10$
- Item 3: The total is $x_1 + x_2 = 15$

## Code Block (Should Not Be Confused with Math)

```python
# This is code, not math
result = 5 * 10  # result = 50
```

## Normal Markdown (Reference)

This is a normal paragraph with **bold** and *italic* text.

A list:
- First item
- Second item
- Third item

A table:

| A | B | C |
|---|---|---|
| 1 | 2 | 3 |
| 4 | 5 | 6 |

---

## Conclusion

All rendering elements should display correctly:
1. YAML frontmatter extracted and styled
2. Inline `$...$` rendered as math bitmaps
3. Block `$$...$$` rendered as math bitmaps
4. Malformed LaTeX shows raw text fallback
5. Currency notation behavior documented (greedy JLatexMath)
6. Normal Markdown unaffected
