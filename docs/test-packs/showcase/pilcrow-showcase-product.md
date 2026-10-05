# Quick Reorder

**One tap to buy the same thing again.** Customers who order every week should not have to rebuild their basket each time.

Quick Reorder adds a button to every past order. It copies the items into a new basket, checks stock and prices, and takes the customer straight to checkout.

## Priorities

| Priority | Item | Status |
| -------- | ---- | ------ |
| P0 | Reorder button | Done |
| P0 | Stock and price check | Building |
| P1 | Swap missing items | Planned |
| P1 | Reorder from the list | Planned |
| P2 | Weekly reminder | Idea |

> [!NOTE]
> Prices are always taken from today's catalogue, never from the old order. The customer sees a clear message if anything changed.

## Acceptance checklist

- [x] The button appears on orders from the last 12 months
- [x] Items missing from stock are listed, not silently dropped
- [ ] Changed prices are shown before checkout
- [x] The basket keeps quantities from the original order
- [ ] Works the same on phone and desktop

## Success measures

- Share of orders that start with Quick Reorder: **25%** within three months
- Time from opening the app to checkout: under **40 seconds**
- Support questions about repeat orders: down by a third

<details>
<summary>Open questions</summary>

- Should a reorder include items that were refunded last time?
- How do we treat items that changed size, for example from 500 g to 450 g?
- Do we offer the button on orders placed as gifts?

</details>

<details>
<summary>Out of scope for this release</summary>

Subscriptions, scheduled deliveries and sharing a basket between accounts. These come after we see how often customers use the first version.

</details>
