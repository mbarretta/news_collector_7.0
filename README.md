# News Collector
Fetch top news from AP and [NewsAPI](https://newsapi.org) into Elasticsearch. Optionally, you can enrich the data with entity and location information extracted via the [Rosette API](https://developer.rosette.com/).

The two main classes are:
- NewCollector: does the news collection
- EntityMomentum: does the entity momentum calculation described below

Each of the main classes has a `main()`, a Lambda `RequestHandler`, and a Dockerfile that gets built by Gradle.

The entity "momentum" metric tries to assign a score to entities each day based on their change in mentions over the past few days:
```
log(avg(M_t)) * min( S / 5, 2) * ( (M_1 / M_0) + (M_2 / M_1) )

where:
- S = number of sources mentioning the entity during the period
- M = mentions
- M_t = total mentions during period
- M_x = mention for a given day in the period
```

The intuition is to look at the change in mentions from the start of the period to the end and boost the score in a diminishing way for entities with a lot of mentions during that period, and also boost if those mentions occured across a lot of different sources (10 sources delivers the max 2x boost)

## Config
The collector is configured via `properties.groovy` (create your own via the supplied example in `src/main/resources`).

### Authentication
If you set an Elasticsearch user using one of the various config methods, then you'll need to also send a password. If you don't configure a user, it'll figure you don't have authentication setup! And shame on you if you don't. 

#### News API
If you don't want to get a NewsAPI key, no worries! Just leave that config blank and it'll be skipped
