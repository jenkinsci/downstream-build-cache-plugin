# Downstream Build Cache Plug-in
Maintains a cache of the upstream / downstream build relationships and makes it available
for other plugins.

## Changelog
See
[CHANGELOG.md](https://github.com/jenkinsci/downstream-build-cache-plugin/blob/master/CHANGELOG.md)
for release details.

## Implementations
Yet Another Build Visualizer plugin uses the downstream build cache to build the build flow graph: 
https://github.com/jenkinsci/yet-another-build-visualizer-plugin

## REST API: Downstream build tree
The plugin exposes an HTTP endpoint that returns the full downstream build tree for a given build,
serialized as JSON or XML:

```
GET /downstream-build-tree/json?project=<projectFullName>&build=<buildNumber>
GET /downstream-build-tree/xml?project=<projectFullName>&build=<buildNumber>
```

* `project` is the full name of the project (e.g. `my-folder/my-job`).
* `build` is the build number of the root build.

The complete tree is returned by default; use the standard `?tree=` query parameter to trim the
response. Builds the current user is not allowed to read are omitted, and an unknown or unreadable
root build returns `404`.

Each node in the tree describes a build/run with the following fields:

| Field              | Description                                                            |
|--------------------|------------------------------------------------------------------------|
| `url`              | Build URL relative to the Jenkins root.                                |
| `number`           | Build number.                                                          |
| `fullName`         | Full display name of the build (e.g. `my-job #5`).                     |
| `externalizableId` | Externalizable id uniquely identifying the build.                      |
| `building`         | `true` if the build is currently in progress.                          |
| `result`           | Build result (`SUCCESS`, `FAILURE`, ...), absent while still building. |
| `projectFullName`  | Full name of the build's project.                                      |
| `children`         | Downstream builds triggered by this build (same structure, recursive). |

Example response:

```json
{
  "projectFullName": "parent",
  "number": 1,
  "fullName": "parent #1",
  "externalizableId": "parent#1",
  "url": "job/parent/1/",
  "building": false,
  "result": "SUCCESS",
  "children": [
    {
      "projectFullName": "child",
      "number": 1,
      "fullName": "child #1",
      "externalizableId": "child#1",
      "url": "job/child/1/",
      "building": false,
      "result": "SUCCESS",
      "children": []
    }
  ]
}
```

**Note:** If the downstream cache recently has been cleaned, or Jenkins recently has been restarted,
the tree might be incomplete until the cache has finished (re)building.

## Contributions
Please use google-java-format with default settings for code formatting. PRs containing code
that is not formatted correctly will not be merged.
