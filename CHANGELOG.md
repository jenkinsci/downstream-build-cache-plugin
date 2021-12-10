# Changelog

## v1.7
* **Major change**: GC:ing of the cache is now opt-in. To enable GC again, set the following system property to "true": com.axis.system.jenkins.plugins.downstream.cache.BuildCache.GC_ENABLED.
  Check https://github.com/jenkinsci/downstream-build-cache-plugin/pull/10 for reasoning.

## v1.6
* **Feature**: New pipeline step, downstreamBuilds, which provides downstream builds for a given build

## v1.5.2
* Fix: Queue related performance fixes

## v1.5.1
* Fix: Minor synchronization fixes

## v1.5
* **Feature**: Build the cache in the background. Jenkins UI will no longer be blocked during the cache loading phase on Jenkins startup.

## v1.4.1
* Fix: Extra NPE-guards [JENKINS-60504]

## v1.4
* Fix: Concurrency bug

## v1.3
* **Feature**: Helper methods to find downstream jobs in the queue

## v1.2
* First public release.
