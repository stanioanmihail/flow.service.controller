package net.boomerangplatform.kube.service;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import io.kubernetes.client.models.V1ConfigMap;
import io.kubernetes.client.models.V1ConfigMapProjection;
import io.kubernetes.client.models.V1Container;
import io.kubernetes.client.models.V1EnvVar;
import io.kubernetes.client.models.V1HostAlias;
import io.kubernetes.client.models.V1Job;
import io.kubernetes.client.models.V1JobSpec;
import io.kubernetes.client.models.V1LocalObjectReference;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1PersistentVolumeClaimVolumeSource;
import io.kubernetes.client.models.V1PodSpec;
import io.kubernetes.client.models.V1PodTemplateSpec;
import io.kubernetes.client.models.V1ProjectedVolumeSource;
import io.kubernetes.client.models.V1SecurityContext;
import io.kubernetes.client.models.V1Volume;
import io.kubernetes.client.models.V1VolumeMount;
import io.kubernetes.client.models.V1VolumeProjection;

@Service
@Profile("cicd")
public class CICDKubeServiceImpl extends AbstractKubeServiceImpl {
	
	@Value("${kube.image}")
	private String kubeImage;
	
	final static String ORG = "bmrg";
	
	final static String PREFIX = ORG + "-cicd";
	
	final static String PREFIX_JOB = PREFIX + "-worker";
	
	final static String PREFIX_CFGMAP = PREFIX + "-cfg";
	
	final static String PREFIX_PVC = PREFIX + "-pvc";
	
	final static String PREFIX_VOL = PREFIX + "-vol";
	
	final static String PREFIX_VOL_DATA = PREFIX_VOL + "-data";
	
	final static String PREFIX_VOL_PROPS = PREFIX_VOL + "-props";
	
	@Override
	protected V1Job createJobBody(String componentName, String componentId, String activityId, String taskName, String taskId, List<String> arguments, Map<String, String> taskInputProperties) {

		// Set Variables
		final String volMountPath = "/cache";
		final String cfgMapMountPath = "/props";

		// Initialize Job Body
		V1Job body = new V1Job(); // V1Job |
		
		// Create Metadata
		V1ObjectMeta jobMetadata = new V1ObjectMeta();
		jobMetadata.annotations(createAnnotations(componentName, componentId, activityId, taskId));
		jobMetadata.labels(createLabels(componentId, activityId, taskId));
//		jobMetadata.generateName(PREFIX_JOB + "-");
		jobMetadata.name(PREFIX_JOB + "-" + activityId);
		body.metadata(jobMetadata);

		// Create Spec
		V1JobSpec jobSpec = new V1JobSpec();
		V1PodTemplateSpec templateSpec = new V1PodTemplateSpec();
		V1PodSpec podSpec = new V1PodSpec();
		V1Container container = new V1Container();
		container.image(kubeImage);
		container.name("worker-cntr");
		container.imagePullPolicy(kubeImagePullPolicy);
		V1SecurityContext securityContext = new V1SecurityContext();
		securityContext.setPrivileged(true);
//		Only works with Kube 1.12. ICP 3.1.1 is Kube 1.11.5
//		securityContext.setProcMount("Unmasked");
		container.setSecurityContext(securityContext);
		List<V1EnvVar> envVars = new ArrayList<V1EnvVar>();
		if (proxyEnabled) {
			envVars.addAll(createProxyEnvVars());
		}
		envVars.add(createEnvVar("DEBUG",kubeWorkerDebug.toString()));
		container.env(envVars);
		container.args(arguments);
		if (checkPVCExists(componentId, null, null, true)) {
			V1VolumeMount volMount = new V1VolumeMount();
			volMount.name(PREFIX_VOL_DATA);
			volMount.mountPath(volMountPath);
			container.addVolumeMountsItem(volMount);
			V1Volume workerVolume = new V1Volume();
			workerVolume.name(PREFIX_VOL_DATA);
			V1PersistentVolumeClaimVolumeSource workerVolumePVCSource = new V1PersistentVolumeClaimVolumeSource();
			workerVolume.persistentVolumeClaim(workerVolumePVCSource.claimName(getPVCName(componentId, null)));
			podSpec.addVolumesItem(workerVolume);
		}
		//Container ConfigMap Mount
		V1VolumeMount volMountConfigMap = new V1VolumeMount();
		volMountConfigMap.name(PREFIX_VOL_PROPS);
		volMountConfigMap.mountPath(cfgMapMountPath);
		container.addVolumeMountsItem(volMountConfigMap);
		
		//Creation of Projected Volume for multiple ConfigMaps
		V1Volume volumeProps = new V1Volume();
		volumeProps.name(PREFIX_VOL_PROPS);
		V1ProjectedVolumeSource projectedVolPropsSource = new V1ProjectedVolumeSource();
		List<V1VolumeProjection> projectPropsVolumeList = new ArrayList<V1VolumeProjection>();
		
		//Add Worfklow Configmap Projected Volume
		V1ConfigMap wfConfigMap = getConfigMap(componentId, activityId, null);
		if (wfConfigMap != null && !getConfigMapName(wfConfigMap).isEmpty()) {
			V1ConfigMapProjection projectedConfigMapWorkflow = new V1ConfigMapProjection();
			projectedConfigMapWorkflow.name(getConfigMapName(wfConfigMap));
			V1VolumeProjection configMapVolSourceWorkflow = new V1VolumeProjection();
			configMapVolSourceWorkflow.configMap(projectedConfigMapWorkflow);
			projectPropsVolumeList.add(configMapVolSourceWorkflow);	
		}
		//Add Task Configmap Projected Volume
		V1ConfigMap taskConfigMap = getConfigMap(componentId, activityId, taskId);
		if (taskConfigMap != null && !getConfigMapName(taskConfigMap).isEmpty()) {
			V1ConfigMapProjection projectedConfigMapTask = new V1ConfigMapProjection();
			projectedConfigMapTask.name(getConfigMapName(taskConfigMap));
			V1VolumeProjection configMapVolSourceTask = new V1VolumeProjection();
			configMapVolSourceTask.configMap(projectedConfigMapTask);
			projectPropsVolumeList.add(configMapVolSourceTask);
		}
		
		//Add all configmap projected volume
		projectedVolPropsSource.sources(projectPropsVolumeList);
		volumeProps.projected(projectedVolPropsSource);
		podSpec.addVolumesItem(volumeProps);
		
		List<V1Container> containerList = new ArrayList<V1Container>();
		containerList.add(container);
		podSpec.containers(containerList);
		
		if (!kubeWorkerHostAliases.isEmpty()) {
			Type listHostAliasType = new TypeToken<List<V1HostAlias>>() {}.getType();
			List<V1HostAlias> hostAliasList = new Gson().fromJson(kubeWorkerHostAliases, listHostAliasType);
	        podSpec.hostAliases(hostAliasList);
		}
		
		if (!kubeWorkerServiceAccount.isEmpty()) {
	        podSpec.serviceAccountName(kubeWorkerServiceAccount);
		}
		
		V1LocalObjectReference imagePullSecret = new V1LocalObjectReference();
		imagePullSecret.name(kubeImagePullSecret);
		List<V1LocalObjectReference> imagePullSecretList = new ArrayList<V1LocalObjectReference>();
		imagePullSecretList.add(imagePullSecret);
		podSpec.imagePullSecrets(imagePullSecretList);
		podSpec.restartPolicy(kubeWorkerJobRestartPolicy);
		templateSpec.spec(podSpec);
		
		//Pod metadata. Different to the job metadata
		V1ObjectMeta podMetadata = new V1ObjectMeta();
		podMetadata.annotations(createAnnotations(componentName, componentId, activityId, taskId));
		podMetadata.labels(createLabels(componentId, activityId, taskId));
		templateSpec.metadata(podMetadata);
		
		jobSpec.backoffLimit(kubeWorkerJobBackOffLimit);
		jobSpec.template(templateSpec);
		Integer ttl = 60*60*24*kubeWorkerJobTTLDays;
		System.out.println("Setting Job TTL at " + ttl + " seconds");
		jobSpec.setTtlSecondsAfterFinished(ttl);
		body.spec(jobSpec);
		
		return body;
	}
	
	protected V1ConfigMap createTaskConfigMapBody(
		      String componentName,
		      String componentId,
		      String activityId,
		      String taskName,
		      String taskId,
		      Map<String, String> inputProps) {
		    V1ConfigMap body = new V1ConfigMap();
		    
		    // Create Metadata
		    V1ObjectMeta metadata = new V1ObjectMeta();
		    metadata.annotations(createAnnotations(componentName, componentId, activityId, taskId));
		    metadata.labels(createLabels(componentId, activityId, taskId));
		    metadata.generateName(PREFIX_CFGMAP);
		    body.metadata(metadata);
		    
		    //Create Data
		    Map<String, String> inputsWithFixedKeys = new HashMap<String, String>();
		    inputsWithFixedKeys.put("task.input.properties", createConfigMapProp(inputProps));
		    body.data(inputsWithFixedKeys);
		    return body;
		  }
	
	protected V1ConfigMap createWorkflowConfigMapBody(
			      String componentName,
			      String componentId,
			      String activityId,
			      Map<String, String> inputProps) {
			    V1ConfigMap body = new V1ConfigMap();
			    
			    // Create Metadata
			    V1ObjectMeta metadata = new V1ObjectMeta();
			    metadata.annotations(createAnnotations(componentName, componentId, activityId, null));
			    metadata.labels(createLabels(componentId, activityId, null));
			    metadata.generateName(PREFIX_CFGMAP);
			    body.metadata(metadata);
			    
			    //Create Data
			    Map<String, String> inputsWithFixedKeys = new HashMap<String, String>();
			    Map<String, String> sysProps = new HashMap<String, String>();
			    sysProps.put("activity.id", activityId);
			    sysProps.put("workflow.name", componentName);
			    sysProps.put("workflow.id", componentId);
			    sysProps.put("worker.debug", kubeWorkerDebug.toString());
			    sysProps.put("controller.service.url", bmrgControllerServiceURL);
			    inputsWithFixedKeys.put("workflow.input.properties", createConfigMapProp(inputProps));
			    inputsWithFixedKeys.put("workflow.system.properties", createConfigMapProp(sysProps));
			    body.data(inputsWithFixedKeys);
			    return body;
			  }

  protected String getLabelSelector(String componentId, String activityId, String taskId) {
	  StringBuilder labelSelector = new StringBuilder("platform=" + ORG + ",app=" + PREFIX);
	  Optional.ofNullable(componentId).ifPresent(str -> labelSelector.append(",component-id=" + str));
	  Optional.ofNullable(activityId).ifPresent(str -> labelSelector.append(",activity-id=" + str));  
    Optional.ofNullable(taskId).ifPresent(str -> labelSelector.append(",task-id=" + str));

	System.out.println("  labelSelector: " + labelSelector.toString());
    return labelSelector.toString();
  }
  
	protected Map<String, String> createAnnotations(String componentName, String componentId, String activityId, String taskId) {
		Map<String, String> annotations = new HashMap<String, String>();
		annotations.put("boomerangplatform.net/platform", ORG);
		annotations.put("boomerangplatform.net/app", PREFIX);
		annotations.put("boomerangplatform.net/component-name", componentName);
		annotations.put("boomerangplatform.net/component-id", componentId);
		annotations.put("boomerangplatform.net/activity-id", activityId);
		Optional.ofNullable(taskId).ifPresent(str -> annotations.put("boomerangplatform.net/task-id", str));
		
		return annotations;
	}
	
	protected Map<String, String> createLabels(String componentId, String activityId, String taskId) {
		Map<String, String> labels = new HashMap<String, String>();
		labels.put("platform", ORG);
		labels.put("app", PREFIX);
		Optional.ofNullable(componentId).ifPresent(str -> labels.put("component-id", str));
		Optional.ofNullable(activityId).ifPresent(str -> labels.put("activity-id", str));
		Optional.ofNullable(taskId).ifPresent(str -> labels.put("task-id", str));
		return labels;
	}
}
