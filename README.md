# Boomerang Flow Controller Service

This service handles and translates the requests that go to Kubernetes.

It uses the Kubernetes Java Client to interact with Kubernetes.

When writing new controller integrations, it is recommended to look through the Docs to find the exact Client method to use and then look at the API code to see how it works for advance configurations such as the Watcher API.

## Development

When running the service locally you need access to a kubernetes API endpoint

## RBAC

The controller and the workers need to run with special RBAC for their specific actions.

### Verification

`kubectl auth can-i create pods/exec --as=system:serviceaccount:bmrg-dev:bmrg-flow-controller`

## References

### Kubernetes Java Client

- Client: https://github.com/kubernetes-client/java
- Examples: https://github.com/kubernetes-client/java/blob/master/examples/src/main/java/io/kubernetes/client/examples
- API: https://github.com/kubernetes-client/java/tree/master/kubernetes/src/main/java/io/kubernetes/client/apis
- Docs: https://github.com/kubernetes-client/java/tree/master/kubernetes/docs

### Kubernetes ConfigMap

We currently use projected volumes however subpath was considered.

- Projected Volumes: https://unofficial-kubernetes.readthedocs.io/en/latest/tasks/configure-pod-container/projected-volume/
- Projected Volumes: https://docs.okd.io/latest/dev_guide/projected_volumes.html
- Projected Volumes: https://stackoverflow.com/questions/49287078/how-to-merge-two-configmaps-using-volume-mount-in-kubernetes
- SubPath: https://blog.sebastian-daschner.com/entries/multiple-kubernetes-volumes-directory

## Stash

### Lifecycle Container Hooks

The following code was written to interface with the container lifecycle hooks of postStart and preStop however there were two main issues:
1. no guarantee that postStart would execute before the main container code -> we went with an initContainer
2. preStop was not executing on jobs when the pod didn't get sent a SIG as it completed successfully so was technically never terminated.

```
V1Lifecycle lifecycle = new V1Lifecycle();
V1Handler postStartHandler = new V1Handler();
V1ExecAction postStartExec = new V1ExecAction();
postStartExec.addCommandItem("/bin/sh");
postStartExec.addCommandItem("-c");
postStartExec.addCommandItem("touch /lifecycle/lock");
postStartHandler.setExec(postStartExec);
lifecycle.setPostStart(postStartHandler);
V1Handler preStopHandler = new V1Handler();
V1ExecAction preStopExec = new V1ExecAction();
preStopExec.addCommandItem("/bin/sh");
preStopExec.addCommandItem("-c");
preStopExec.addCommandItem("rm -f /lifecycle/lock");
preStopHandler.setExec(preStopExec);
lifecycle.setPreStop(preStopHandler);
container.lifecycle(lifecycle);
```

- PreStop Hooks arent called on Successful Job: https://github.com/kubernetes/kubernetes/issues/55807
- https://kubernetes.io/docs/concepts/workloads/pods/pod/#termination-of-pods
- https://v1-13.docs.kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/
- https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/

### Sidecars

- Sidecar Containers in Jobs: https://github.com/kubernetes/kubernetes/issues/25908
- Sidecar Containers in Jobs 2: https://stackoverflow.com/questions/36208211/sidecar-containers-in-kubernetes-jobs
- Terminating a sidecar container: https://medium.com/@cotton_ori/how-to-terminate-a-side-car-container-in-kubernetes-job-2468f435ca99
- Sidecar Container Design Patterns: https://www.weave.works/blog/container-design-patterns-for-kubernetes/
- KEP (Kubernetes Enhancement Proposal for Sidecars: https://github.com/kubernetes/enhancements/blob/master/keps/sig-apps/sidecarcontainers.md#upgrade--downgrade-strategy

### Output Properties
- Argo Variables: https://github.com/argoproj/argo/blob/master/docs/variables.md
- Argo Output Parameters: https://github.com/argoproj/argo/blob/master/examples/README.md#output-parameters
- Container Namespace Sharing: google it
