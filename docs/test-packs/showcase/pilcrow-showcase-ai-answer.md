# How to plan a week of meals on a budget

You can eat well for about €35 to €45 a week per person if you plan around a few cheap staples, cook in batches and let leftovers do the work. Here is a simple way to do it.

## A sample week

| Day | Dinner | Leftovers |
| --- | ------ | --------- |
| Mon | Lentil soup | - |
| Tue | Baked potatoes | - |
| Wed | Soup and toast | Mon |
| Thu | Chicken and rice | - |
| Fri | Fried rice | Thu |
| Sat | Tomato pasta | - |
| Sun | Tray bake, eggs | Sat |

## The method

1. **Check what you already have.** Look through the fridge, freezer and cupboards first. Plan two meals around whatever needs using up.
2. **Pick two cheap proteins.** Dried lentils, beans, eggs and chicken thighs cost far less per portion than most alternatives.
3. **Cook once, eat twice.** Make a double batch of soup or sauce. Freeze half in single portions.
4. **Write the list by recipe, not by shelf.** Every item on the list should belong to a meal you have named.
5. **Buy loose and in season.** Loose vegetables are usually cheaper than pre-packed ones, and seasonal produce is cheaper still.

## Costs at a glance

- Dried lentils, per portion: about €0.20
- Rice or pasta, per portion: about €0.25
- Chicken thighs, per portion: about €1.10
- A vegetable-only dinner: about €1.00 to €1.50

## Check your total before you shop

If you like numbers, a few lines are enough to add up a list and see the cost per dinner:

```python
basket = {
    "lentils 1 kg": 2.40,
    "rice 1 kg": 1.90,
    "chicken thighs": 5.50,
    "carrots 1 kg": 0.90,
    "tinned tomatoes x4": 2.20,
}

total = sum(basket.values())
dinners = 7
print(f"Total: €{total:.2f}")
print(f"Each: €{total / dinners:.2f}")
```

> Tip: shop with a full stomach and a written list. Both save money.

If the total is above your limit, swap one meat dinner for a bean dish and run the numbers again.
