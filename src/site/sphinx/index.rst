Olog Service Documentation!
===========================

Olog is an online logbook service which allows for the creation and retrieval of log entries.

The service was developed to address the needs of operators, engineers, and users of large scientific facilities.

Key features:
 - Integration with CS-Studio, Phoebus, Bluesky, and other controls and data acquisition tools.
 - Tags & Logbooks provide an effective way to organize and sort log entries
 - Categorization of a log entry supported through the "level" meta-data field.
 - Additional (searchable) meta-data offered through "properties".
 - Support for fuzzy searching
 - Markup support for creating rich text log entries. Markup is based on the Commonmark specification, extended
   with support for image size and tables. Clients may request a HTML formatted quick reference (maintained
   in the project) resource using an URL like http(s)://url.to.service/CommonmarkCheatsheet.html.


A Log Entry:
############

.. code-block:: json

   {
      "id":575,
      "owner":"testOwner1",
      "source":"**Beam Dump** due to Major power dip Current Alarms Booster transmitter switched back to lower state.
                PV : BR-RF{Xmtr-PLC}ICS:Down-Sts",
      "description":"Beam Dump due to Major power dip Current Alarms Booster transmitter switched back to lower state.
                     PV : BR-RF{Xmtr-PLC}ICS:Down-Sts",
      "level":"Info",
      "title":"Some title",
      "state":"Active",
      "createdDate":1577392617217,
      "logbooks":[
         {
            "name":"ControlsOperations",
            "owner":"operators"
         }
      ],
      "tags":[
         {
            "name":"Fault"
         }
      ],
      "properties":[
         {
            "name":"FaultReport",
            "owner":"testOwner1",
            "attributes":[
               {
                  "name":"id",
                  "value":"1234"
               },
               {
                  "name":"URL",
                  "value":"https://nsls2.bnl.gov/faults/1234"
               }
            ]
         }
      ],
      "attachments":[
         {
            "id":"5e21fe829fef8a53f0183d4a",
            "filename":"screenshot_image.png",
            "fileMetadataDescription":"image.png"
         }
      ],
      "events":[
         {
            "name":"faultTime",
            "instant":1577389011004
         }
      ]
   }

LogEntry
************
| The most important filed of any log entry are the description and owner.
| **description:** Is the text body of a log entry.
| **owner:** The creator of the log entry.
| **level** Info, Warning, 

| Other fields include
| **source:** When markup support is enabled the description text + the markup annotations are stored in this field.
| **id:** The unique identifier for a log entry, this is automatically generated at the time of creation.
| **createTime:** Another automatically generated field which stores the unix epoch millisecond at which the log entry was created.

Attachment
************

Each log entry can have a list attachments, these can be any type of files.

Logbooks & Tags
***************

Logbooks and Tags are useful organization tools. Each log entry can be associated with one or more logbooks and have zero or more tags.


Properties
************

A property is a list of key value pairs. The provide means of attaching meta data to a log entry,
this data can be used to integrate with other services or capture context information.
 
Some examples include

A property to link log entries to Tickets 

.. code-block:: json

   {
    "name":"ticket",
    "attributes":[
       {
          "name":"id",
          "value":"1234"
       },
       {
          "name":"URL",
          "value":"https://trac.nsls2.bnl.gov/ticket/1234"
       }]
   }

events
*******

There are instances when the log entry being created is actually associated with an event that happened some time ago. 
The users had higher priority tasks to address at that moment and is able to log the event after those tasks.
The using **events** allows users to associate log entries with different instances in time,
time based searches will ensure that these log entries are also found even if the create time might not fall in the search range.

Quick Start
############

Download and install elasticsearch (verision 8.3) from elastic.com
Download and install mongodb from mongodb

Configure the service
The configuration files for Phoebus Olog are present under phoebus-olog/tree/master/src/main/resources/applications.properties

Build
::

   mvn clean install

Start the service
::

   mvn org.springframework.boot:spring-boot-maven-plugin:run

Detailed Installation Instructions:
`Install Phoebus Olog <https://github.com/Olog/phoebus-olog#installation>`_.

REST API 
#########

Creating a Log Entry
***********************

Create a simple log entry 

NOTE: deprecated, will be removed in future commits. Replaced by https://localhost:8181/Olog/logs/multipart
**PUT** https://localhost:8181/Olog/logs

.. code-block:: json

 {
      "owner":"log",
      "description":"Beam Dump due to Major power dip Current Alarms Booster transmitter switched back to lower state.",
      "level":"Info",
      "title":"Some title",
      "logbooks":[
         {
            "name":"Operations"
         }
      ]
 }

Create a log entry, optionally with file attachments

**PUT** https://localhost:8181/Olog/logs/multipart

.. code-block:: json

 {
    "owner":"log",
      "description":"Beam Dump due to Major power dip Current Alarms Booster transmitter switched back to lower state.",
      "level":"Info",
      "title":"Some title",
      "logbooks":[
         {
            "name":"Operations"
         }
      ],
      "attachments":[
         {"id": "82dd67fa-09df-11ee-be56-0242ac120002", "filename":"MyScreenShot.png"},
         {"id": "c02948ad-4bbd-432f-aa4d-a687a54f8d40", "filename":"MySpreadsheet.xlsx"}
      ]
 }

**NOTE** Attachment ids must be unique, e.g. UUID. When creating a log entry - optionally with attachments - client **must**:

#. Use a multipart request and set the Content-Type to "multipart/form-data", even if no attachments are present.
#. If attachments are present: add one request part per attachment file, in the order they appear in the log entry. Each
file must be added using "files" as the name for the part.
#. Add the log entry as a request part with content type "application/json". The name of the part must be "logEntry".

Client must also be prepared to handle a HTTP 413 (payload too large) response in case the attached files exceed
file and request size limits configured in the service.


Reply to a log entry. This uses the same end point as when creating a log entry, but client must
send the unique id of the log entry to which the new one is a reply.

**PUT** https://localhost:8181/Olog/logs?inReplyTo=<id>

If <id> does not identify an existing log entry, a HTTP 400 status is returned.

Adding an attachment 

**POST** https://localhost:8181/Olog/logs/attachments/{logId}

.. code-block:: HTML
 
 Content-Type: multipart/form-data; boundary=----formBoundary
 ------formBoundary
 Content-Disposition: form-data; name="filename"
 Content-Type: application/json
 {"image1.png"}
 ------formBoundary
 Content-Disposition: form-data; name="fileMetadataDescription"
 Content-Type: application/json
 {"image/png"}
 ------formBoundary
 Content-Disposition: form-data; name="file "; filename="image1.png"
 Content-Type: application/octet-steam
 {…file content…}
 ------formBoundary--



Searching for Log Entries
**************************

**GET** https://localhost:8181/Olog/logs

Search Parameters

+---------------+------------------------------------------------------------------+
|Keyword        | Descriptions                                                     |
+===============+==================================================================+
| **Text search**                                                                  |
+---------------+------------------------------------------------------------------+
|*text*         | A list of keywords which are present in the log entry description|
+---------------+------------------------------------------------------------------+
|*fuzzy*        | Allow fuzzy searches                                             |
+---------------+------------------------------------------------------------------+
|*phrase*       | Finds log entries with the exact same word/s                     |
+---------------+------------------------------------------------------------------+
|*owner*        | Finds log entries with the given owner                           |
+---------------+------------------------------------------------------------------+
| **Time based searches**                                                          |
+---------------+------------------------------------------------------------------+
|*start*        | Search for log entries created after given time instant          |
+---------------+------------------------------------------------------------------+
|*end*          | Search for log entries created before the given time instant     |
+---------------+------------------------------------------------------------------+
|*includeevents*| A flag to include log event times when                           |
+---------------+------------------------------------------------------------------+
| **Meta Data searches**                                                           |
+---------------+------------------------------------------------------------------+
|*tags*         | Search for log entries with at least one of the given tags       |
+---------------+------------------------------------------------------------------+
|*logbooks*     | Search for log entries with at least one of the given logbooks   |
+---------------+------------------------------------------------------------------+
| **Attachments searches**                                                         |
+---------------+------------------------------------------------------------------+
|*attachments*  | To search for entries with at least one attachment               |
+---------------+------------------------------------------------------------------+
| **Pagination searches**                                                          |
+---------------+------------------------------------------------------------------+
|*size*         | The number of log entries to be returned within each page        |
+---------------+------------------------------------------------------------------+
|*page*         | The page number, i.e page 1 is the 1 to 1+size log               |
|               |  entries matching the search                                     |
+---------------+------------------------------------------------------------------+
|*Sorting Search Results*                                                          |
+---------------+------------------------------------------------------------------+
|*sort*         | `up|down` order the search results based on create time          |
+---------------+------------------------------------------------------------------+

For time based search requests the client may specify a **tz** parameter indicating the client's time zone.
The format must be recognized as a valid zone identifier, see for instance https://docs.oracle.com/javase/8/docs/api/java/time/ZoneId.html.
If the client does not specify the time zone, the time zone of the service is used to compute start end end timestamps.
An invalid time zone specifier will result in a HTTP 400 (bad request) response.

Example:

**GET** https://localhost:8181/Olog/logs/search?desc=dump&logbooks=Operations

The above search request will return all log entires with the term "dump" in their 
descriptions and which are part of the Operations logbook.

Retrieving an attachment of a log entry
 
**GET** https://localhost:8181/Olog/logs/attachments/{logId}/{filename}

Find entries with at least one attachment of type 'image'

**GET** https://localhost:8181/Olog/logs/search?attachments=image

Updating a Log Entry
********************

**POST** https://localhost:8181/Olog/logs/{logId}

Update a log entry, the orginal log entry is archived in a seperate elastic index before any of the changes are applied.

Note: the create date, attachments, and events cannot be modified.

.. code-block:: json

 {
      "owner":"log",
      "description":"Beam Dump due to Major power dip Current Alarms Booster transmitter switched back to lower state.
                     New important info appended",
      "level":"Info",
      "title":"A new title",
      "logbooks":[
         {
            "name":"Operations"
         }
      ]
 }

Managing Logbooks, Tags and Levels
**********************************

Retrieve the list of existing tags
 
**GET** https://localhost:8181/Olog/tags

Retrieve the list of existing logbooks

**GET** https://localhost:8181/Olog/logbooks

Retrieve the list of existing levels

**GET** https://localhost:8181/Olog/levels


Create a new tag

**PUT** https://localhost:8181/Olog/tags/{tagName}

.. code-block:: json

 https://localhost:8181/Olog/tags/Fault

 {
      "name":"Fault",
      "state":"Active"
 }
 
Create multiple tags

**PUT** https://localhost:8181/Olog/tags
  
.. code-block:: json

 https://localhost:8181/Olog/tags

 [
   {"name":"Fault", "state":"Active" },
   {"name":"Alarm", "state":"Active" }
 ]
 
Create a new logbook

**PUT** https://localhost:8181/Olog/logbooks/{logbookName}

.. code-block:: json

 https://localhost:8181/Olog/logbooks/Operations

 {
      "name":"Operations",
      "owner":"olog-logs",
      "state":"Active"
 }

**Official experiment logbooks**

The single and bulk PUT endpoints accept ``officialExperiment`` (boolean,
default ``false``) and ``experimentLdapPath`` (string). The path is mandatory
when ``officialExperiment`` is true. Only the logbook owner or a logbook
administrator may change an existing logbook. The owner of a new logbook is
always the authenticated creator; a client-supplied owner is ignored.

For example, PUT ``/Olog/logbooks/ATLAS`` with:

.. code-block:: json

 {
   "name": "ATLAS",
   "state": "Active",
   "officialExperiment": true,
   "experimentLdapPath": "i:infn:lnf:csn1:atlas"
 }

Authenticated experiment affiliates receive READ access to
all entries and attachments in the logbook, including searches and counts,
without individual membership records. This group grant is independent of
visibility dates on individual records. Group membership does not grant WRITE
or ADMIN. Other users retain access granted through their individual ACLs.
Global application administrator roles do not grant logbook administration.

Experiment membership is obtained from INFN Identity's GET ``/get/auth/{uuid}``,
using the authenticated user's UUID and forwarding the request's Bearer token.
The principal identifier configured by ``oauth2.claimsName`` must be the INFN
UUID, as for explicit memberships.
The response must have ``errorCode: 0``; only ``payload.isMemberOf`` is used.
For an experiment path ``i:infn:lnf:csn1:atlas``, the role
``i:infn:lnf:csn1:atlas::aff`` or any of its colon-separated descendants
(for example ``i:infn:lnf:csn1:atlas::aff:tng``) grants READ. Roles for
``atlas2``, ``atlas::affiliation`` or ``atlas::resp:local`` do not match.
Token claims and Spring authorities are not used as experiment membership.

The permissions/me endpoints combine explicit permissions with experiment
READ grants, returning each accessible logbook once. Authorization data is
loaded once per user per HTTP request and refreshed on the next request.
If no Bearer token is available or INFN Identity fails, automatic experiment
READ is not granted; explicit logbook ACLs continue to apply. The service URL
is configured with ``infn.identity.base-url`` (or ``INFN_IDENTITY_BASE_URL``),
defaulting to ``https://api.app.infn.it/identity``. Custom gateway URLs must
include the Identity service path, so the final request is
``/identity/get/auth/{uuid}`` on the default gateway.

If an experiment logbook is missing from ``/Olog/logbooks/permissions/me``,
check that it is Active, ``officialExperiment`` is true, and its
``experimentLdapPath`` contains the base experiment path (for example
``i:infn:lnf:csn1:atlas``), without an ``::aff`` or ``::resp`` suffix.
The frontend must send its Bearer token on this request, and the authenticated
principal must identify the INFN UUID rather than a login name.
Set ``logging.level.org.phoebus.olog.identity.ExperimentMembershipService=DEBUG``
to see the required affiliation, number of memberships and match result.
Warnings distinguish a missing Bearer token, an upstream HTTP error and an
invalid API response with its error code; token values and response bodies
are not logged.

An administrator can grant an experiment member individual WRITE permission
using POST ``/Olog/logbooks/ATLAS/members``:

.. code-block:: json

 {"infnUuid": "user-uuid", "permissions": ["WRITE"]}

For an existing individual membership, use PUT
``/Olog/logbooks/ATLAS/members/user-uuid`` with ``{"permissions":["WRITE"]}``.
Removing that individual membership revokes WRITE while the experiment READ
grant remains. The permissions/me endpoints include the effective group READ
permission. Anonymous access to entries is rejected with 401; authenticated
users without the necessary permission receive 403.

Existing documents without the new fields remain ordinary logbooks. The
Elasticsearch logbook mapping adds a boolean and a keyword without changing
existing fields or requiring reindexing. Existing indices with default dynamic
mapping accept these fields on the first write; deployments that disable
dynamic mapping must add both fields using the Elasticsearch mapping API
before writing experiment logbooks.

Create multiple logbooks

**PUT** https://localhost:8181/Olog/logbooks

.. code-block:: json

 https://localhost:8181/Olog/logbooks

 [
   {"name":"Operations", "owner":"olog-logs", "state":"Active"},
   {"name":"DAMA",       "owner":"olog-logs", "state":"Active"}
 ]

 Create a new level

 **PUT** https://localhost:8181/Olog/level/{levelName}

 .. code-block:: json

  https://localhost:8181/Olog/level/Info

  {
       "name":"Info",
       "defaultLevel":[true|false]
  }

**NOTE**: only one single level may be defined as default level.

Managing Properties
*******************

Retrieve the list of existing properties
 
**GET** https://localhost:8181/Olog/properties

Create a new property

**PUT** https://localhost:8181/Olog/properties/{propertyName}

.. code-block:: json

 {
      "name":"Ticket",
      "owner":"olog-logs",
      "state":"Active",
      "attributes":[
         {
            "name":"id",
            "state":"Active"
         },
         {
            "name":"url",
            "state":"Active"
         }
      ]
 }

Create multiple properties

**PUT** https://localhost:8181/Olog/properties

.. code-block:: json

 [
   {
      "name":"Ticket",
      "owner":"olog-logs",
      "state":"Active",
      "attributes":[
         {"name":"id", "state":"Active"},
         {"name":"url", "state":"Active"}
      ]
   },
      {
      "name":"Scan",
      "owner":"olog-logs",
      "state":"Active",
      "attributes":[
         {"name":"id", "state":"Active"}
      ]
   }
 ]

Templates
#########

Log entry templates can be added to the storage to support use cases when the same type of log entries need to be
created on a regular basis. Templates have the same structure a regular log entries, except for attachments.

To add a new template, use:

**PUT** https://localhost:8181/Olog/templates

.. code-block:: json

{
   "description":"Template text",
   "level":"Info",
   "title":"Some title",
   "logbooks":[
      {
         "name":"Operations"
      }
   ]
}

In the client UI (currently only CS Studio/Phoebus) users may select from a list of templates, if available. Upon
selection of a template, the client will populate the editor's input controls based on the template content.

Authentication
##############

In general, non-GET methods are protected, i.e. client needs to send a basic authentication header for each request.
Alternatively, client may use a session cookie returned upon successful authentication with the login endpoint:

**POST** https://localhost:8181/Olog/login

.. code-block:: json

{"username":"johndoe", "password":"undisclosed"}

Developer Documentation:
########################

.. toctree::
   :maxdepth: 1

   changelog

Storage consumption and quota requests
**************************************

``GET /Olog/storage/me`` returns consumption by owned logbook. Each item in
``logbooks`` includes its own ``quotaBytes`` and latest increase ``request``.
Every logbook has an independent default quota of 1,000,000,000 bytes (1 GB). The measurement is ``JSON_SOURCE_UTF8``:
compact UTF-8 JSON documents stored in Elasticsearch, including logbook
metadata, current entries, archived versions and collaborator records.
It is not the compressed Lucene disk footprint, replicas, translog or binary
attachments held in MongoDB. Inactive logbooks are included: deactivation
does not delete their stored entries or release their quota.

Per-logbook amounts include the complete entry when an entry belongs to
several logbooks. The user's total counts an entry once when several of
those logbooks belong to the same creator; that aggregate has no global limit.
Collaborator writes consume each associated logbook's quota. All matching documents are scanned in pages; this
calculation is not restricted by the normal logbook or log search size.

Writes that would increase consumption beyond quota return HTTP 507 with
``code: STORAGE_QUOTA_EXCEEDED``. Reads and increase requests remain available.
Same-size replacements and reductions are accepted even when the quota has
already been reached. Archived versions also consume quota, so an edit that
archives the previous version may require additional space.
Quota admission and document indexing are serialized within one Olog instance.
Deployments with several writer instances must serialize writes through one
instance; writes made directly to Elasticsearch bypass the application quota.

``POST /Olog/storage/requests`` requests a larger total quota for one logbook:

.. code-block:: json

 {"logbook": "ATLAS", "requestedQuotaBytes": 2000000000, "reason": "Additional experiment history"}

The requester identifier is always taken from authentication. Only an administrator
of the specified logbook may submit the request. The request does not increase the
quota immediately. Only one PENDING request is allowed per logbook;
a second pending request returns 409. The latest request and its decision
remain in Elasticsearch's ``olog_storage_quotas`` index, configurable with
``elasticsearch.storage.quota.index``. New installations create the index
using ``storage_quota_mapping.json``; installations disabling automatic index
creation must provision it with that mapping first.

Only global Olog administrators identified by the INFN responsibility
entitlement below may use ``GET /Olog/storage/requests`` to list pending
requests, or review a request with
``POST /Olog/storage/requests/{logbook}/{requestId}/review``:

.. code-block:: json

 {"approved": true, "note": "Approved for the next run"}

Approval assigns the requested total quota to the specified logbook only.
Rejection preserves the existing
quota. Decisions record the reviewer, time and note. Stale or competing
updates return 409. Logbook ownership, collaborator ADMIN and experiment
membership do not grant the right to list or review quota requests. Global
administration requires the exact INFN entitlement described below.
Anonymous requests return 401 and unauthorized authenticated requests return
403. No email is sent: administrators review requests in the Admin panel.

Existing per-user quota records remain stored but no longer apply to logbooks.
Pending per-user requests must be resubmitted for the relevant logbook. Quota
document IDs now use a ``logbook:`` prefix. Startup adds the ``logbook`` keyword
field to existing quota mappings. Deploy the corresponding frontend together.

Quota administration panel
==========================

Global administrators can open the web client's ``/admin/storage`` page using
its Admin navigation button. This page lists pending requests with current and
requested quota, requester, reason and request date, and supports filtering,
refresh, approval and rejection with a review note. Quota indicators and
increase-request forms remain next to the logbooks in Your logbooks.

``GET /Olog/storage/permissions/me`` returns ``{"administrator": true}`` for
an authenticated global Olog administrator and ``{"administrator": false}``
for other authenticated users; anonymous access returns 401. This lookup
uses INFN Identity authorization and does not scan log entries or stored
quota documents. The frontend uses it to gate both navigation and
direct access to the panel. Listing and reviewing requests independently
require administrator permissions on the backend.

Olog administrator entitlement
-----------------------------

Global Olog administrators are authenticated users with the following exact
entitlement in ``payload.isMemberOf`` or ``payload.eduPersonEntitlement``
returned by INFN Identity's ``GET /get/auth/{uuid}``:

.. code-block:: text

 i:infn:lnf:divisione_ricerca:servizio_infrastrutturale_calcolo_e_reti:reparto_sviluppo_applicazioni::n:resp

The lookup forwards the request's Bearer token and uses the authenticated
INFN UUID. The complete authorization response is reused only within the
current HTTP request, including experiment membership checks. Parent paths,
suffixed roles and legacy application administrator authorities do not grant
this privilege. Failure to retrieve valid authorization data denies global
administration. All quota listing and review endpoints enforce the same
policy independently of the frontend's navigation.

Logbook export
--------------

``GET /Olog/logbooks/{name}/export?format=json`` downloads every current log
entry for the exact logbook, ordered by creation time and id. ``format=pdf``
produces a paginated text PDF. The default format is JSON; unsupported formats
return 400. This endpoint requires authentication (401) and logbook administrator
permission (403). Global Olog administrator status alone does not grant access.

Replies and inactive entries are included; historical archived versions and binary
attachments are excluded. Attachment metadata is retained. JSON returns an envelope
with ``formatVersion``, ``exportedAt``, ``logbook``, ``logs`` and ``logCount``.
PDF renders descriptions and metadata; unsupported font glyphs use Unicode code
point notation. Downloads use attachment disposition and ``Cache-Control: no-store``.

The export scans every Elasticsearch result page. Generation completes before the
response starts; failures return 503. Temporary files are deleted after download
or generation failure. ``olog.export.temp-directory`` selects the export directory
(default JVM temporary directory); PDF scratch files use the JVM temporary directory.
