# Prompt

## Role

You are expert `Spring Boot` developer

## Goal

Have a `Spring Boot` application which covers requirements described below

## Task

Develop a `Spring Boot` application which covers the following requirements:

### Data Model

The application should have the following entities:

#### KeyValue

- id - string, primary key, maximum length 64
- value - string, non-null, maximum length 256

#### AuditLog

- id - string, primery key, guid, auto-generated
- entry type - enum, possible values: regular, minute consolidation, hour consolidation, day consolidation, week consolidation, month consolidation, year consolidation
- operation type - enum, possible values: create, read, update, delete
- date time - timestamp, UTF
- count - int
- duration - int

### Functionalities

#### Warmup

When the application starts, it should check that its data is "warmed up". This means that there are a certain number of `KeyValue` entities. If the number of available `KeyValue` entities is below the warm up number (defined by environment variable, default value 1 000 000), the `Warmup` procedure creates the needed number of entities so that the desired warmup number of entities is reached. Values for `id` and `value` fields are auto-generated. The `Warmup` functionality is blocking - this means that no other functionality is executed before `Warmup` operation completes. To track the progress of the `Warmup` operation, the application should print proper logs to stdout.

#### Load

The application should perform random operations in endless loop. The operation to be performed should be chosen randomly between the following options: create, read, update, delete. The steps included in each operation are described below:

- create

Create and persist a new `KeyValue` entity. Use auto-generated strings for `id` and `value` properties. If the operation fails because the entity already exists, ignore the error.

- read

Retrieve the first `KeyValue` entity. Now, this may sound strange, but here is the reason for that. The goal of the operation is to retrieve a single entity. It does not matter which one. Retrieving a random entity is harder to implement, considering the primary key is auto-generated string. This is why the simplest approach is just to retrieve the first entity, considering the table is sorted by primary key.

- update

Retrieve the first `KeyValue` entity. See above why the first. Update the `value` property with a newly generated random string. Persist the entity in the database.

- delete

Retrieve and delete the first `KeyValue` entity. See above why exactly the first entity.

Cross-cuttin concerns:

Each operations should be audit logged. This means that after each operation a new `AuditLog` entity should be created. Here are the values for its fields:

- id - auto-generated string
- entry type - regular
- operation type - the type of the operation being audit logged
- count - 1
- duration - the duration of the operation in milliseconds

#### Consolidation

To avoid `AuditLog` entities growing indefinetely, the application should perform periodic consolidation of the existing `AuditLog` entities. The application should perform 6 types of consolidations as described below:

- minute

Runs every minute.

Reads all the "old" (to be detailed later) regular (entry type = regular) `AuditLog` entities. Groups them by `operation type` and the minute of the `date time` field. For each of the groups, creates a respective `AuditLog` entry where the `count` field is the number of entities from the group, and `duration` is the average value from the `duration` fields of the entries in the group.

What "old" means: if the consolidation runs at 12:23:34, it filters all the `AuditLog` entities whose date time is < 12:23:00.

All the "consolidated" `AuditLog` entities are deleted.

- hour

Runs every minute.

The logic is the same as in "minute" consolidation, except the `entry type` and the filtering.

- day

Runs at 00:00:00.

The logic is the same as in "minute" consolidation, except the `entry type` and the filtering.

- week, month, year

I guess you get the idea at this point in time.

### UI

The application should have a UI implemented with Thymeleaf and Bootstrap frameworks.

The application should display two of the applications entities:

- KeyValue

Simply list the entitues. Use paging. Display 20 records per page.

- AuditLog

Simply list the entities. Use paging. Display 20 records per page.

Make it possible to filder by `entry type` and `operation type`

### Deployment

Author `Makefile` which makes it possible to run the application with `docker compose`. The application will need a database to persist its data. Use `MySQL` as database. Provision and configure both the database and the application so that they work together.
