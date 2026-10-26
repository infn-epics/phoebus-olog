# Olog   [![Build Java Binary and Run Tests](https://github.com/Olog/phoebus-olog/actions/workflows/java-build-and-test.yml/badge.svg)](https://github.com/Olog/phoebus-olog/actions/workflows/java-build-and-test.yml)

An online logbook service that allows for the creation and retrieval of log entries.

[Phoebus Olog Documentation](https://olog.readthedocs.io/)

### Installation
Olog 

* Prerequisites

  * JDK 17 or newer
  * Elastic version 8.2.x
  * mongo gridfs


 **Download links for the prerequisites**   
 Download and install elasticsearch (verision 8.2.x) from [elastic.com](https://www.elastic.co/downloads/past-releases/elasticsearch-8-2-3)    
 Download and install mongodb from [mongodb](https://www.mongodb.com/download-center/community)    
  
  
* Configure the service (optional)
The configuration files for olog-es are present under `phoebus-olog/tree/master/src/main/resources` 


* Build 
```
cd phoebus-olog
mvn clean install
``` 

* Build deployable jar

To build a jar with dependencies and Tomcat server, use Maven profile `deployable-jar`, e.g.:
```
cd phoebus-olog
mvn -Pdeployable-jar clean install
```

#### Start the service  

Using spring boot  

```
mvn org.springframework.boot:spring-boot-maven-plugin:run
```

#### Check if service is running

Once the service is running, the service consists of a welcome page `http://localhost:8080/Olog` 

```
$ curl http://localhost:8080/Olog
{
  "name" : "Olog Service",
  "version" : "5.0.0-SNAPSHOT",
  "elastic" : {
    "status" : "Connected",
    "clusterName" : "elastic-nasa",
    "clusterUuid" : "QNeYpFlWRueYPH3uXGUiGw",
    "version" : "co.elastic.clients.elasticsearch._types.ElasticsearchVersionInfo@79c2137f",
    "elasticHost" : "localhost",
    "elasticPort" : "9200"
  },
  "mongoDB" : "{type=STANDALONE, servers=[{address=localhost:27017, type=STANDALONE, roundTripTime=1.2 ms, state=CONNECTED}]",
  "serverConfig" : {
    "maxFileSize" : 50.0,
    "maxRequestSize" : 100.0
  }
}
```

This will provide information about the version of the Olog service running,
along with information about the version and connection status of the elastic and mongo
backends.

### INFN Identity client

The INFN Identity Java client is generated at build time with OpenAPI Generator from
`src/main/openapi/infn-identity-v3.json`. Generated sources are written under `target/generated-sources/openapi`
and must not be edited or committed. Run `mvn generate-sources` to regenerate them.

The generated `IdentityApi` can be created for a user bearer token through
`InfnIdentityClientFactory`. The API base URL defaults to `https://api.app.infn.it/identity` and can be overridden with
the `INFN_IDENTITY_BASE_URL` environment variable.

### Logbook collaborators

The authenticated creator of a logbook becomes its administrator. An administrator can manage collaborators with:

* `GET /Olog/logbooks/{name}/members`
* `POST /Olog/logbooks/{name}/members`
* `PUT /Olog/logbooks/{name}/members/{infnUuid}`
* `DELETE /Olog/logbooks/{name}/members/{infnUuid}`
* `GET /Olog/logbooks/{name}/permissions/me`
* `GET /Olog/logbooks/permissions/me`

The request payload accepts an INFN UUID and one or both collaborator permissions:

```json
{
  "infnUuid": "123e4567-e89b-12d3-a456-426614174000",
  "permissions": ["READ", "WRITE"],
  "visibleFrom": "2026-09-01",
  "visibleTo": "2026-09-30"
}
```

The `ADMIN` permission is reserved for the logbook creator and cannot be delegated through the collaborators API.
`visibleFrom` and `visibleTo` are optional ISO calendar dates (`YYYY-MM-DD`). They restrict
the **creation date of entries**, not the dates on which a collaborator may sign in.
Both days are inclusive in UTC; either end may be omitted. Missing or null dates mean
no limit on that side. Sending both as null on PUT removes the restriction. PUT replaces
the collaborator's permissions and period, so clients must resend dates they want to retain.
Invalid dates and reversed intervals return 400. Existing memberships without these
fields remain unrestricted in time; owners and ADMIN memberships always have full history.

Log reads now require authentication (401 for anonymous requests). A collaborator with
READ or WRITE can view entries within their period. Direct access outside the period
returns 403; search, group queries, RSS and archived versions are filtered before pagination
and counting. Attachments require access to a referencing current entry. For entries in
multiple logbooks, any valid grant permits reading the entry. Global roles/groups do not
replace a logbook grant. Updating an existing entry also requires visibility of that entry.
Clients must send credentials for image/download requests too; the web client uses
authenticated requests and temporary blob URLs for attachments.

The membership mapping adds nullable date fields; no rewrite of old documents is needed.
New indices use `logbook_member_mapping.json`; existing default dynamic mappings accept
the ISO date values. For installations with dynamic mapping disabled, add `visibleFrom`
and `visibleTo` as `date` fields (`strict_date`) before using the feature.
Creating or editing a log entry requires `WRITE` (or `ADMIN`) on every associated logbook.
OAuth deployments must configure `OAUTH2_CLAIMS_NAME` with the JWT claim containing the INFN UUID;
the default is `preferred_username`. Demo basic authentication is disabled by default and can be enabled with
`DEMO_AUTH_ENABLED=true` for local development.

An example for testing the creation of a single test log entry with the demo credentials 

```
curl --location --insecure --request PUT 'https://localhost:8181/Olog/logs' \
--header 'Content-Type: application/json' \
--header 'Authorization: Basic YWRtaW46YWRtaW5QYXNz' \
--data '{
             "owner": "test-owner",
             "source": "This is an test entry",
             "title": "Tes title",
             "level": "Info",
             "logbooks": [{"name": "operations","owner": "olog-logs"}]
         }'
```

### Running using Docker Compose

**Prerequisites**

* Docker Compose
* Adequate 'mmap count' for ElasticSearch: https://www.elastic.co/guide/en/elasticsearch/reference/current/vm-max-map-count.html
  * On linux, you can run `sysctl -w vm.max_map_count=262144` to set this
* Adequate memory lock limit for ElasticSearch
  * You can check this with `ulimit -l` - you can set it to `unlimited` but the command varies

**Run**

* Build the image: `docker-compose build`
* Run the containers: `docker-compose up`

### Integration tests with Docker containers

Purpose is to have integration tests for Olog API with Docker.

See `src/test/java` and package
* `org.phoebus.olog.docker`

Integration tests start docker containers for Olog, Elasticsearch and MongoDB, and run http requests (GET) and curl commands (POST, PUT, DELETE) towards the application to test behavior (read, list, query, create, update, remove) and replies are received and checked if content is as expected.

There are tests for properties, tags, logbooks and logs separately and in combination.

Integration tests can be run in IDE and via Maven.

```
mvn failsafe:integration-test -DskipITs=false -Pintegrationtest-docker
```

See
* [How to run Integration test with Docker](src/test/resources/INTEGRATIONTEST_DOCKER_RUN.md)
* [Tutorial for Integration test with Docker](src/test/resources/INTEGRATIONTEST_DOCKER_TUTORIAL.md)

### Release Olog Server binaries to maven central

The Phoebus Olog service uses the maven release plugin to prepare the publish the olog server binaries to maven central
using the sonatype repositories.

**Setup**

Create a sonatype account and update the maven settings.xml file with your sonatype credentials

```
  <servers>
   <server>
      <id>phoebus-releases</id>
      <username>shroffk</username>
      <password>*******</password>
   </server>
  </servers>
```

**Prepare the release**  
`mvn release:prepare`  
In this step will ensure there are no uncommitted changes, ensure the versions number are correct, tag the scm, etc..
A full list of checks is documented [here](https://maven.apache.org/maven-release/maven-release-plugin/examples/prepare-release.html):

**Perform the release**  
`mvn release:perform`  
Checkout the release tag, build, sign and push the build binaries to sonatype.
**NOTE:** Mac OS users should invoke `export GPG_TTY=$(tty)` prior to `mvn release:perform`.

**Publish**  
Open the staging repository in [sonatype](https://s01.oss.sonatype.org/#stagingRepositories) and hit the *publish* button

### Releasing Docker Images

Docker images are published to the GitHub Container Registry via GitHub actions. This is triggered by:
  - push to master
  
Images are published to the `ghcr.io/<org name>/<group id>-<artifact id>` registry under the following tags:
  - `latest`
  - `<version>`
  - `<version>-<timestamp>`

This tag information is extracted from the Maven POM (via the `help` plugin). In order to avoid defining all of this 
in the GitHub actions themselves, the generation of these tag names is delegated to shell scripts in the `scripts` folder. 

If you need to debug issues related to registry/tag names outside of GitHub's CI/CD environment, 
you can run these scripts locally:

```
# setup your environment with GitHub's enviroment vars and files
source ./scripts/setup-locally.sh

# Build the tag names from Maven etc and write them to PROJECT_INFO.txt
./scripts/write-project-info.sh

# Append the vars in PROJECT_INFO.txt to the GitHub environment file 
# (setup-locally.sh uses THE_GITHUB_ENV_FILE.txt, but in GH CI/CD 
# it is unique per run)
# This file should match PROJECT_INFO.txt
./scripts/set-github-env.sh
```

What you should see is:
  - registry and tag names are valid; e.g. are all lowercase and use dashes instead of spaces
  - registry and tag names should not be null/empty/unrelated to this project

If you would like to read up more on how passing environment variables between steps in a GitHub action works, 
please see their docs on [Job Outputs](https://docs.github.com/en/actions/using-jobs/defining-outputs-for-jobs). 

### Storage quotas

`GET /Olog/storage/me` reports per-logbook Elasticsearch JSON data usage and the
creator's total consumption, with an independent default quota of 1,000,000,000 bytes
(1 GB) for each logbook. The creator's aggregate has no global limit. The
measurement includes current and archived logs and collaborator metadata;
MongoDB attachment binaries and the physical Lucene/index footprint are excluded.
Inactive logbooks continue to count. Shared entries count once in the user's
aggregate and in full in each associated logbook's breakdown.

Writes that increase usage beyond a logbook's quota return HTTP 507 with
`STORAGE_QUOTA_EXCEEDED`; read access and quota requests remain available.
`POST /Olog/storage/requests` accepts `logbook`, `requestedQuotaBytes` (the new
total quota for that logbook) and a reason. Only an administrator of that logbook
can submit a request; approval changes that logbook alone. Global Olog administrators can list pending requests and approve
or reject them in the Admin panel. Requests are stored in
`olog_storage_quotas` (`elasticsearch.storage.quota.index`), provisioned from
`storage_quota_mapping.json`. Global quota administration requires the exact
INFN entitlement documented below, obtained from `/get/auth/{uuid}`.

Admission checks and indexing are serialized inside one writer instance.
A deployment with multiple writers must route writes through one instance;
direct Elasticsearch writes bypass these application-level checks. Usage is
calculated by paged scans of stored documents, so its cost grows with history.
Review requests using `POST /Olog/storage/requests/{logbook}/{requestId}/review`.
See the API documentation for the full request and review contract.

Per-logbook quota records use document IDs prefixed with `logbook:`. Existing per-user
quota records are retained but do not apply to logbooks; pending per-user requests must
be resubmitted for the relevant logbook. Startup adds the `logbook` keyword field to an
existing quota index. Deploy the backend and frontend together for this API change.

### Olog administrator identity

The Admin panel and quota review APIs require this exact INFN responsibility
entitlement:

```text
i:infn:lnf:divisione_ricerca:servizio_infrastrutturale_calcolo_e_reti:reparto_sviluppo_applicazioni::n:resp
```

The backend queries INFN Identity's `/get/auth/{uuid}` with the authenticated
user's UUID and Bearer token, then matches `payload.isMemberOf` or
`payload.eduPersonEntitlement`. The authorization response is cached only for
the current HTTP request and is shared with experiment membership checks.
The entitlement must match exactly. Legacy `ROLE_ADMIN` authorities or
logbook ADMIN grants do not substitute for this responsibility. If Identity
is unavailable or returns an invalid response, administrator access is denied.
The frontend uses the server's `administrator` result for its navigation and
admin page; it does not infer this privilege from local token roles.

### Logbook exports

Logbook owners and delegated logbook administrators can download JSON or PDF via
`GET /Olog/logbooks/{name}/export?format=json` (or `format=pdf`). Authentication
is required (401); READ/WRITE membership or global Olog administrator status alone
does not grant export access (403). JSON is the default format; other formats
return 400. Responses are attachments with `Cache-Control: no-store`.

Exports include all current entries associated with that exact logbook, including
replies and inactive entries, ordered by creation time and id. JSON preserves
stored fields in a versioned envelope (`formatVersion`, `exportedAt`, `logbook`,
`logs`, `logCount`). PDF provides paginated text and metadata, with unsupported
font glyphs represented as Unicode code points. Attachment metadata is included;
binary attachment contents and historical archived versions are excluded.

Elasticsearch results are scrolled beyond normal search page limits. The complete
file is generated before download; generation failures return 503 and temporary
files are removed. Ensure sufficient temporary disk space; optionally configure
`olog.export.temp-directory` (default JVM temporary directory) for export files.
PDFBox also uses the JVM temporary directory for its scratch storage.
