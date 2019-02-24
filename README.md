# Play REST API

API responsible for storing and managing fantasy league teams and standings

### leaderboards support:
- supports daily/weekly leaderboards as well as whole league leaderboards
- supports a 'previousRank' field so can show up/down arrows for recent progress
- auto-rollover of days/periods (time to rollover can be specified)

### team support:
- ability to verify transfers are valid before making them
- update a team in bulk (multiple sell/buy at once)
- configurable user money (1 decimal place)
- ability to open and close transfer window
- can autoclose transfer window whilst games in play
- can add an optional 'wildcard' (reset team and set user money back to starting money)
- can specify team size
- can specify custom limits (i.e. only 4 defenders. only 3 players from team X). limit can be applied to everything (i.e. 3 players per team), or different numbers for each instance of limit (i.e. 4 defenders, but 2 strikers)
- optional transfer delay

### other support
- lets you add any number of extra scoring fields, which are leaderboard accessible
- supports points multipliers for different days/weeks
- updating of players cost


### Running

requires jdk 1.8+ and scala 2.something?
requires postgresql 9+ installed
with that should just be able to git clone, then sbt run from project directory

starts on port 9000

bit hackily first have to create tables and add admin api user by calling home index (just localhost:9000), hopefully this will be better in future

### Usage

there are lots of example api calls in the notes.txt (some may 404 due to syntax changing a little)

### Info
runs on scala play frame-work
currently uses squeryl for db access/stuff, but will be replaced with scala slick
permissive license so anyone can use/build upon themselves without issue
