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
- lets you store match details/results/meta-data


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

its not 'very restful', but thats because most actions tend to have multiple consequences in a fantasy league.
i.e. buying a hero needs to update team, as well as remove user credits
add a result needs to add the result, but also update user stats, update pickee stats etc

now could make itt so one endpoint has one responsibility, and you just POST to add/update one part of the consequences
(i.e. split adding result into POST the match, then POST stat updates, and POST pickee updates). but this just adds complexity to apiUser's code.

### Glossary
- apiUser: Someone who creates a league for others to participate in. usually a website with the frontend to enable team picking and leaderboard showing
- user: one of the individual users of the league created by apiUser above
- pickee: the players the users can pick and put in their team (I think 'player' can be too easy to confuse with user. pickee is a bit more unique)
- limit: refers to a restriction on the number of types of pickee (pickee types can be specified when creating league)
- period: a chunk of time to split league into, to be able to display separate leaderboards for each period, as well as potentially handle transfer opening/closing. this will usually either be 'Day', or 'Week', depending on how long the league will run for

### Place in fantasy sport ecosystem
fantasy league split into
a) Frontend for picking teams/showing leaderboards
b) higher level user auth layer. what i mean is this api has no concept of user logins. users will login to whatever frontend they are using, that frontend will then send api requests (with the frontend owners apiKey) on behalf of the user to make transfers.

this decoupling is what lets this api work with a regular website, a third party website, a twitch bot, subreddit bot, etc
c) api to handle storing points/data and teams (this is what this repo does!)
d optional) optional because can do it manually. but ideally have a script/program to get match results, turn that into points, and then call api in c) to add matches

as this repo just does c, need to provide a, b and d for yourself.
the fantasy-dota-heroes project in this org is an example of a, b and d

# ideas that lie on the boundary of this api's responsibilities
- auto recalibrating pickee costs
this is currently outside api's responsibility. a separate script would calculate/determine what new values you want. then you simple call updateCosts with these new values. however have thought of way to offer a naive, simple auto recalibrater
- scoring system
api has no idea of scoring system (i.e. generating points, from things separate game stats). this is up to apiUser. because this scoring system will be completely sport/game dependent, anything sport/game dependent is outside of this api
