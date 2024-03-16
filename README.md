An implementation of 1brc challenge - https://github.com/gunnarmorling/1brc

Baseline & measurements creation are copied from the repo mentioned above - all credits to https://github.com/gunnarmorling.  

General rules:

* Input value ranges are as follows:
* Station name: non null `UTF-8` string of min length 1 character and max length 100 bytes, containing neither ; nor \n characters. (i.e. this could be 100 one-byte characters, or 50 two-byte characters, etc.)
* Temperature value: non null double between `-99.9` (inclusive) and `99.9` (inclusive), always with one fractional digit
* There is a maximum of `10,000` unique station names
* Line endings in the file are `\n` characters on all platforms
* Implementations must not rely on specifics of a given data set, e.g. any valid station name as per the constraints above and any data distribution (number of measurements per station) must be supported
* The rounding of output values must be done using the semantics of `IEEE 754` rounding-direction "roundTowardPositive"

Local setup: `MacBookPro2019@2,6 GHz 6-Core Intel Core i7`

Baseline version takes around 3 minutes to complete on `GraalVM 21.0.2`

Unsafe implementation w/ manual hashing & hash maps does `10kk` records in less than a second, around 15 secs on a billion records

Possible ways to improve further - read 8 byte chunks as longs and use masks to strip unnecessary bytes
