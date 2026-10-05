# PilcrowMD test pack – Coders

**What this is.** Code blocks in the languages PilcrowMD colours, including the ones new in version
1.0.12, plus diffs, very long lines and a few edge cases. All the code is short and made up: a tiny
"tide table" program written again and again in different languages.

**What to look for**

- Each block in **Part A** should be coloured: keywords, strings, numbers and comments in different
  colours, in both themes (**Settings → Theme**).
- Each block in **Part B** should be coloured too: these languages are new in version 1.0.12.
- Every code block has a **Copy** button. Copy one and paste it somewhere: the text should be exactly
  what you see, with the same indentation.
- **Part C**: long lines scroll sideways inside the block. Turn on **Settings → Wrap long lines in
  code blocks** and they should wrap instead.
- Try a few blocks in both reading mode and Edit mode.

**Known gaps in version 1.0.12** – we know about these, no need to report them:

- Some short or variant names get no colour: `py`, `python3`, `kt` and `yml`, for example. Write
  `python`, `kotlin`, `yaml` and so on.
- Inside a list, a quote or a callout, a block in one of the languages new in version 1.0.12 (such
  as those in Part B) shows as plain text.
- In Edit mode, the code inside a fence is one flat colour.
- The code font joins some character groups into one symbol: `-->` can look like a single long
  arrow.
- In a ` ```markdown ` block, a quote line with four or more spaces after `>` is coloured orange.
- Diffs colour the `+` and `-` text, but the whole line is not tinted green or red.

Found something not on this list? Please open an issue at
<https://github.com/pilcrowmd/pilcrow/issues> and say which pack and which block.

---

## Part A – coloured languages

### Kotlin

```kotlin
data class Tide(val hour: Int, val heightM: Double)

fun highest(tides: List<Tide>): Tide? = tides.maxByOrNull { it.heightM }

fun main() {
    val today = listOf(Tide(3, 0.4), Tide(9, 4.1), Tide(15, 0.6), Tide(21, 3.9))
    println("High water at ${highest(today)?.hour}:00") // prints 9:00
}
```

### Java

```java
import java.util.List;

public final class TideTable {
    record Tide(int hour, double heightM) {}

    public static void main(String[] args) {
        List<Tide> today = List.of(new Tide(3, 0.4), new Tide(9, 4.1));
        Tide max = today.stream().max((a, b) -> Double.compare(a.heightM(), b.heightM())).orElseThrow();
        System.out.printf("High water at %d:00%n", max.hour());
    }
}
```

### Python

```python
from dataclasses import dataclass

@dataclass
class Tide:
    hour: int
    height_m: float

today = [Tide(3, 0.4), Tide(9, 4.1), Tide(15, 0.6)]
high = max(today, key=lambda t: t.height_m)
print(f"High water at {high.hour}:00")  # 9:00
```

### JavaScript (also as `js`)

```javascript
const today = [{ hour: 3, heightM: 0.4 }, { hour: 9, heightM: 4.1 }];
const high = today.reduce((a, b) => (b.heightM > a.heightM ? b : a));
console.log(`High water at ${high.hour}:00`);
```

```js
export async function loadTides(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}
```

### JSON

```json
{
  "harbour": "Gull Bay",
  "tides": [
    { "hour": 3, "height_m": 0.4, "type": "low" },
    { "hour": 9, "height_m": 4.1, "type": "high" }
  ],
  "verified": true,
  "notes": null
}
```

### C

```c
#include <stdio.h>

int main(void) {
    double heights[] = {0.4, 4.1, 0.6, 3.9};
    int best = 0;
    for (int i = 1; i < 4; i++) {
        if (heights[i] > heights[best]) best = i;
    }
    printf("Highest tide: %.1f m\n", heights[best]);
    return 0;
}
```

### C++

```cpp
#include <algorithm>
#include <iostream>
#include <vector>

int main() {
    std::vector<double> heights{0.4, 4.1, 0.6, 3.9};
    auto high = *std::max_element(heights.begin(), heights.end());
    std::cout << "Highest tide: " << high << " m\n";
}
```

### C# (`csharp`)

```csharp
using System.Linq;

var heights = new[] { 0.4, 4.1, 0.6, 3.9 };
Console.WriteLine($"Highest tide: {heights.Max():F1} m");
```

### Go

```go
package main

import "fmt"

func main() {
	heights := []float64{0.4, 4.1, 0.6, 3.9}
	high := heights[0]
	for _, h := range heights[1:] {
		if h > high {
			high = h
		}
	}
	fmt.Printf("Highest tide: %.1f m\n", high)
}
```

### Swift

```swift
struct Tide { let hour: Int; let heightM: Double }

let today = [Tide(hour: 3, heightM: 0.4), Tide(hour: 9, heightM: 4.1)]
if let high = today.max(by: { $0.heightM < $1.heightM }) {
    print("High water at \(high.hour):00")
}
```

### Dart

```dart
class Tide {
  final int hour;
  final double heightM;
  const Tide(this.hour, this.heightM);
}

void main() {
  final today = [const Tide(3, 0.4), const Tide(9, 4.1)];
  final high = today.reduce((a, b) => b.heightM > a.heightM ? b : a);
  print('High water at ${high.hour}:00');
}
```

### Scala

```scala
case class Tide(hour: Int, heightM: Double)

@main def run(): Unit =
  val today = List(Tide(3, 0.4), Tide(9, 4.1))
  println(s"High water at ${today.maxBy(_.heightM).hour}:00")
```

### Groovy

```groovy
def today = [[hour: 3, heightM: 0.4], [hour: 9, heightM: 4.1]]
def high = today.max { it.heightM }
println "High water at ${high.hour}:00"
```

### Clojure

```clojure
(def today [{:hour 3 :height-m 0.4} {:hour 9 :height-m 4.1}])

(defn highest [tides] (apply max-key :height-m tides))

(println "High water at" (:hour (highest today)) ":00")
```

### SQL

```sql
SELECT harbour, MAX(height_m) AS high_m
FROM tides
WHERE observed_on = DATE '2026-10-02'
GROUP BY harbour
HAVING COUNT(*) >= 4
ORDER BY high_m DESC;
```

### HTML (`html`, also `xml`, `svg`, `markup`)

```html
<!doctype html>
<table class="tides">
  <tr><th>Hour</th><th>Height</th></tr>
  <tr><td>09:00</td><td>4.1 m</td></tr>
</table>
<!-- a comment -->
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tides harbour="Gull Bay">
  <tide hour="9" type="high">4.1</tide>
</tides>
```

### CSS

```css
.tides th {
  color: #3a6ea5;
  font-weight: 600;
}
@media (prefers-color-scheme: dark) {
  .tides { background: #101418; }
}
```

### Makefile

```makefile
.PHONY: test clean

test:
	python -m unittest discover tests

clean:
	rm -rf build/
```

### LaTeX source

```latex
\documentclass{article}
\begin{document}
High water is at $9{:}00$, height $h = 4.1\,\mathrm{m}$.
\end{document}
```

### Markdown source

````markdown
# A heading inside a code block

- a list item
- **bold** stays literal here

```python
print("an inner fence stays visible")
```
````

### Git output, diff and patch

```git
commit 1a2b3c4d
Author: Example Person <person@example.org>

    Add the afternoon tide
```

```diff
--- a/tides.csv
+++ b/tides.csv
@@ -1,3 +1,4 @@
 hour,height_m
 3,0.4
-9,4.0
+9,4.1
+15,0.6
```

```patch
@@ -10,2 +10,2 @@ def highest(tides):
-    return max(tides)
+    return max(tides, key=lambda t: t.height_m)
```

### Brainfuck (yes, really)

```brainfuck
++++++++[>++++++++<-]>+.   prints the letter A
```

### YAML

```yaml
harbour: Gull Bay
tides:
  - hour: 9
    height_m: 4.1 # high water
verified: true
```

## Part B – coloured since version 1.0.12

### Shell

```bash
#!/usr/bin/env bash
set -euo pipefail
for file in data/*.csv; do
  printf 'Checking %s\n' "$file"
  tail -n +2 "$file" | sort -t, -k2 -nr | head -1
done
```

### TypeScript

```typescript
type Tide = { hour: number; heightM: number };
const highest = (tides: Tide[]): Tide | undefined =>
  tides.reduce<Tide | undefined>((a, b) => (!a || b.heightM > a.heightM ? b : a), undefined);
```

### Rust

```rust
struct Tide { hour: u8, height_m: f64 }

fn main() {
    let today = [Tide { hour: 3, height_m: 0.4 }, Tide { hour: 9, height_m: 4.1 }];
    let high = today.iter().max_by(|a, b| a.height_m.total_cmp(&b.height_m)).unwrap();
    println!("High water at {}:00", high.hour);
}
```

### Ruby

```ruby
Tide = Struct.new(:hour, :height_m)
today = [Tide.new(3, 0.4), Tide.new(9, 4.1)]
puts "High water at #{today.max_by(&:height_m).hour}:00"
```

### PHP

```php
<?php
$today = [['hour' => 3, 'h' => 0.4], ['hour' => 9, 'h' => 4.1]];
usort($today, fn($a, $b) => $b['h'] <=> $a['h']);
echo "High water at {$today[0]['hour']}:00\n";
```

### PowerShell

```powershell
$today = @(@{Hour=3; Height=0.4}, @{Hour=9; Height=4.1})
$high = $today | Sort-Object { $_.Height } -Descending | Select-Object -First 1
Write-Output "High water at $($high.Hour):00"
```

### Lua

```lua
local today = { {hour = 3, h = 0.4}, {hour = 9, h = 4.1} }
table.sort(today, function(a, b) return a.h > b.h end)
print("High water at " .. today[1].hour .. ":00")
```

### R

```r
today <- data.frame(hour = c(3, 9, 15), height_m = c(0.4, 4.1, 0.6))
cat("High water at", today$hour[which.max(today$height_m)], ":00\n")
```

## Part C – long lines and edge cases

A very long single line (scrolls sideways, or wraps with the setting on):

```python
TIDES = {"gull_bay": [0.4, 4.1, 0.6, 3.9], "seal_point": [0.3, 3.8, 0.5, 3.6], "heron_creek": [0.2, 2.9, 0.4, 2.7], "otter_cove": [0.5, 4.4, 0.7, 4.2]}
```

A long identifier with no spaces at all:

```text
gull_bay_harbour_tide_gauge_number_four_calibration_record_2026_10_02_morning_shift_verified_and_signed
```

Tabs and spaces (the indentation should line up as typed):

```go
func main() {
	if true {
		fmt.Println("tab-indented")
	}
}
```

A fence written with tildes:

~~~python
print("tilde fences work too")
~~~

A language nobody has heard of (plain text, no error):

```tidescript
when tide > 4.0 m: ring bell twice
```

A block with no language at all:

```
no language name here — plain monospace
```

An indented code block (four spaces, no fence):

    hour,height_m
    9,4.1

Inline code with a backtick inside: ``a `tick` inside``. A path: `~/Tides/2026 October/gull-bay.csv`.

Characters the code font might join: `-->` `<=` `>=` `!=` `===` `=>` `::` `<-`.

---

**End of the Coders pack.** This file is public domain (CC0 1.0). Copy, change and share it freely.
