// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** M-225: [EquationTagShim.translate], the render-only rewrite of amsmath equation numbers. */
class EquationTagShimTest {

    private fun t(latex: String): String = EquationTagShim.translate(latex)

    @Test fun tagBecomesParenthesisedNumber() = assertEquals("x = y \\qquad(1)", t("x = y \\tag{1}"))

    @Test fun starredTagBecomesBareLabel() = assertEquals("x = y \\qquad a", t("x = y \\tag*{a}"))

    @Test fun spaceBeforeTheBraceIsAllowed() = assertEquals("x \\qquad(2)", t("x \\tag {2}"))

    @Test fun nestedBracesStayInsideTheTag() = assertEquals("x \\qquad(a_{1})", t("x \\tag{a_{1}}"))

    @Test fun notagAndNonumberAreDropped() = assertEquals("a  + b ", t("a \\notag + b \\nonumber"))

    @Test fun nestedTagIsRewrittenInTheSamePass() = assertEquals("x \\qquad(\\qquad(1))", t("x \\tag{\\tag{1}}"))

    @Test fun idempotent() = t("x \\tag{1} \\notag").let { assertSame(it, t(it)) }

    // --- untouched: the same reference back ---

    @Test fun identityWithoutTag() = "\\frac{a}{b}".let { assertSame(it, t(it)) }

    @Test fun identityLongerMacroName() = "\\tagged{1} \\notags".let { assertSame(it, t(it)) }

    @Test fun identityTagWithoutBraceGroup() = "x \\tag 1".let { assertSame(it, t(it)) }

    @Test fun identityLineBreakFollowedByTagText() = "a \\\\tag{1}".let { assertSame(it, t(it)) }

    @Test fun identityUnclosedTagIsNotGuessed() = "x \\tag{1 + \\notag".let { assertSame(it, t(it)) }

    @Test fun identityTrailingBackslash() = "tag \\".let { assertSame(it, t(it)) }
}
