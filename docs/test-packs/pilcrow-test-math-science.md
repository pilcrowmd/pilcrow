# PilcrowMD test pack – Maths & Science

**What this is.** Formulas from school maths to university physics and chemistry, written in the
LaTeX that most note apps and AI assistants produce. PilcrowMD draws them on your phone, offline.

**What to look for**

- Every formula is drawn as maths, not shown as `$…$` source – except the few in **Part F**, which
  are known gaps and should fall back to their source text without breaking anything around them.
- Inline formulas sit inside the sentence; display formulas (`$$…$$`) are centred on their own line.
- Change **Settings → Preview text size**: formulas grow and shrink with the text.
- Wide formulas should scroll or fit; nothing should be cut off at the right edge.
- Try both themes (**Settings → Theme**): formulas stay readable on both.

**Known gaps in version 1.0.12** – we know about these, no need to report them:

- A formula inside a table cell shows as an empty space.
- Equation numbers with `\tag{1}` make the whole formula show as source text.
- Greek letters typed directly inside `\text{…}` (for example `\text{α}`) make the formula show as
  source text. `\alpha` outside `\text{}` works.
- In chemistry, a space after a number (`\ce{2 H2O}`) makes the formula show as plain text;
  `\ce{2H2O}` works.
- `\( … \)` and `\[ … \]` are not treated as maths; use `$…$` and `$$…$$`.
- Inline formulas sit slightly low in their line.
- When a file is reopened, its formulas can show as source for a moment before they are drawn.

Found something not on this list? Please open an issue at
<https://github.com/pilcrowmd/pilcrow/issues> and say which pack and which formula.

---

## Part A – school maths

Inline: a fraction $\frac{3}{4}$, a power $x^{2}$, a root $\sqrt{2} \approx 1.414$, a subscript
$a_{n+1}$, and a percentage $12.5\%$.

The quadratic formula:

$$
x = \frac{-b \pm \sqrt{b^{2} - 4ac}}{2a}
$$

Pythagoras, and the area and circumference of a circle:

$$
a^{2} + b^{2} = c^{2} \qquad A = \pi r^{2} \qquad C = 2\pi r
$$

A cube root and a nested fraction:

$$
\sqrt[3]{27} = 3 \qquad \frac{1}{1 + \frac{1}{1 + \frac{1}{2}}} = \frac{3}{5}
$$

## Part B – calculus and series

$$
\frac{d}{dx}\sin x = \cos x \qquad \int_{0}^{1} x^{2}\,dx = \frac{1}{3}
$$

$$
\lim_{n \to \infty} \left(1 + \frac{1}{n}\right)^{n} = e
$$

$$
\sum_{k=0}^{\infty} \frac{x^{k}}{k!} = e^{x} \qquad \prod_{k=1}^{n} k = n!
$$

A double integral and a partial derivative:

$$
\iint_{D} f(x, y)\,dx\,dy \qquad \frac{\partial^{2} u}{\partial t^{2}} = c^{2}\,\nabla^{2} u
$$

## Part C – linear algebra and cases

$$
A = \begin{pmatrix} 2 & -1 \\ 0 & 3 \end{pmatrix}, \qquad
\det A = 6, \qquad
B = \begin{bmatrix} 1 & 0 & 0 \\ 0 & 1 & 0 \\ 0 & 0 & 1 \end{bmatrix}
$$

$$
\lvert x \rvert = \begin{cases} x, & x \ge 0 \\ -x, & x < 0 \end{cases}
$$

Aligned equations:

$$
\begin{aligned}
(a + b)^{2} &= a^{2} + 2ab + b^{2} \\
(a - b)^{2} &= a^{2} - 2ab + b^{2}
\end{aligned}
$$

## Part D – statistics and Greek letters

Mean and standard deviation:

$$
\bar{x} = \frac{1}{n}\sum_{i=1}^{n} x_{i} \qquad
\sigma = \sqrt{\frac{1}{n}\sum_{i=1}^{n} (x_{i} - \bar{x})^{2}}
$$

Bayes' rule:

$$
P(A \mid B) = \frac{P(B \mid A)\,P(A)}{P(B)}
$$

The normal distribution:

$$
f(x) = \frac{1}{\sigma\sqrt{2\pi}}\, e^{-\frac{(x - \mu)^{2}}{2\sigma^{2}}}
$$

Greek letters: $\alpha\ \beta\ \gamma\ \delta\ \epsilon\ \theta\ \lambda\ \mu\ \pi\ \rho\ \sigma\ \phi\ \omega$
and capitals $\Gamma\ \Delta\ \Theta\ \Lambda\ \Sigma\ \Phi\ \Omega$.

Symbols: $\le\ \ge\ \ne\ \approx\ \equiv\ \infty\ \pm\ \times\ \div\ \in\ \subset\ \cup\ \cap\ \forall\ \exists\ \rightarrow\ \Rightarrow$

## Part E – physics and chemistry

Newton, energy and momentum: $F = ma$, $E_{k} = \tfrac{1}{2}mv^{2}$, $p = mv$, $E = mc^{2}$.

A vector and its length:

$$
\vec{v} = (3, 4, 0)\ \mathrm{m/s}, \qquad \lVert \vec{v} \rVert = 5\ \mathrm{m/s}
$$

Maxwell's equations:

$$
\nabla \cdot \vec{E} = \frac{\rho}{\varepsilon_{0}} \qquad
\nabla \times \vec{B} = \mu_{0}\vec{J} + \mu_{0}\varepsilon_{0}\frac{\partial \vec{E}}{\partial t}
$$

Units: $9.81\ \mathrm{m\,s^{-2}}$, $1\ \mathrm{J} = 1\ \mathrm{kg\,m^{2}\,s^{-2}}$, $20\,^{\circ}\mathrm{C}$.

Chemistry with `\ce{}`: water $\ce{H2O}$, sulfate $\ce{SO4^2-}$, and the burning of hydrogen

$$
\ce{2H2 + O2 -> 2H2O}
$$

An equilibrium:

$$
\ce{CO2 + H2O <=> H2CO3}
$$

Plain-text chemistry (no maths needed): H₂O, CO₂, Ca²⁺, ¹⁴C, 2 H₂ + O₂ → 2 H₂O.

## Part F – known gaps (these should fall back to source text, without breaking the page)

Equation number:

$$
E = mc^{2} \tag{1}
$$

Greek inside text: $\text{α and β}$

Chemistry with a space after the number: $\ce{2 H2 + O2 -> 2 H2O}$

Other delimiters: \( a^2 + b^2 \) and

\[
a^2 + b^2 = c^2
\]

A formula inside a table:

| Quantity | Formula            |
| -------- | ------------------ |
| Energy   | $E = mc^{2}$       |
| Area     | $A = \pi r^{2}$    |

A formula that cannot be parsed at all: $\frac{1}{$ – the text after it must still show.

The text after it.

---

**End of the Maths & Science pack.** This file is public domain (CC0 1.0). Copy, change and share
it freely.
