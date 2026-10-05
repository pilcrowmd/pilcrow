# Quillcache

A tiny on-disk cache for small apps. No server, no background threads, one file per entry.

Quillcache keeps recent results close to your code so a slow call is made only once. Entries expire on their own, and the cache never grows past the size you set.

## Quick start

Add the library, open a cache, and ask it for a value. If the key is missing, `getOrPut` runs your loader and stores the result.

```kotlin
val cache = Quillcache.open(cacheDir)

val profile = cache.getOrPut("user:42") {
    api.fetchProfile(42)
}

println("Hello, ${profile.name}")
```

The same idea in Python:

```python
from quillcache import Quillcache

cache = Quillcache.open("./cache")

@cache.memoize(ttl_seconds=600)
def load_profile(user_id):
    return fetch_profile(user_id)

print(load_profile(42)["name"])
```

## Configuration

Settings live in a small file called `quillcache.json` next to the cache folder:

```json
{
  "maxBytes": 5000000,
  "defaultTtlSeconds": 600,
  "evict": "least-recently-used",
  "compress": true
}
```

With `compress` on, values larger than 1 KB are compressed before they are written. Use `cache.clear()` to remove everything, or `cache.remove(key)` for one entry.

## Roadmap

- [x] Time-based expiry
- [x] Size limit with least-recently-used eviction
- [x] Kotlin and Python bindings
- [ ] JavaScript binding
- [ ] Optional encryption of stored values
- [ ] Cache statistics

## Contributing

Bug reports and small fixes are welcome. Please read the [contributing guide](https://example.org/quillcache/contributing) first, and look through the [open issues](https://example.org/quillcache/issues) before filing a new one.

Quillcache is released under the MIT licence. Full text at <https://example.org/quillcache/licence>.
