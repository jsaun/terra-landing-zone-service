package bio.terra.landingzone.stairway.flight.create.resource.step;

import bio.terra.landingzone.library.landingzones.definition.ArmManagers;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.KubeConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public class KubernetesClientProviderImpl implements KubernetesClientProvider {
  @NotNull
  public CoreV1Api createCoreApiClient(
      ArmManagers armManagers, String mrgName, String aksClusterName) {
    KubeConfig kubeConfig = loadKubeConfig(armManagers, mrgName, aksClusterName);
    var userToken = kubeConfig.getCredentials().get("token");

    ApiClient client =
        Config.fromToken(kubeConfig.getServer(), userToken)
            .setSslCaCert(
                new ByteArrayInputStream(
                    Base64.getDecoder()
                        .decode(
                            kubeConfig
                                .getCertificateAuthorityData()
                                .getBytes(StandardCharsets.UTF_8))));
    return new CoreV1Api(client);
  }

  @NotNull
  private KubeConfig loadKubeConfig(
      ArmManagers armManagers, String mrgName, String aksClusterName) {
    var rawKubeConfig =
        armManagers
            .azureResourceManager()
            .kubernetesClusters()
            .manager()
            .serviceClient()
            .getManagedClusters()
            .listClusterUserCredentials(mrgName, aksClusterName)
            .kubeconfigs()
            .stream()
            .findFirst()
            .orElseThrow(() -> new RuntimeException("No kubeconfig found"));
    var stream = new ByteArrayInputStream(rawKubeConfig.value());
    FileOutputStream writer = null;
    try {
      writer = new FileOutputStream("kubeconfig.txt");
      writer.write(stream.readAllBytes());
    } catch (Exception e) {
      e.printStackTrace();
    }

    var kubeConfig =
        KubeConfig.loadKubeConfig(
            new InputStreamReader(
                new ByteArrayInputStream(rawKubeConfig.value()), StandardCharsets.UTF_8));
    for (var user : kubeConfig.getUsers()) {
      var model = (Map<String, Object>) (((Map<String, Object>) user).get("user"));
      var exec = (Map<String, Object>) model.get("exec");
      var args = (ArrayList<String>) exec.get("args");
      var argsMap = new HashMap<String, String>();
      for (int i = 2; i < args.size(); i += 2) {
        argsMap.put(args.get(i - 1), args.get(i));
      }
      var servicePrincipalArgs = new String[13];
      servicePrincipalArgs[0] = "get-token";
      servicePrincipalArgs[1] = "--environment";
      servicePrincipalArgs[2] = argsMap.get("--environment");
      servicePrincipalArgs[3] = "--server-id";
      servicePrincipalArgs[4] = argsMap.get("--server-id");
      servicePrincipalArgs[5] = "--client-id";
      servicePrincipalArgs[6] = armManagers.clientId();
      servicePrincipalArgs[7] = "--tenant-id";
      servicePrincipalArgs[8] = armManagers.tenantId();
      servicePrincipalArgs[9] = "--client-secret";
      servicePrincipalArgs[10] = armManagers.clientSecret();
      servicePrincipalArgs[11] = "--login";
      servicePrincipalArgs[12] = "spn";
      exec.put("args", List.of(servicePrincipalArgs));
    }

    System.out.println(armManagers.clientId());
    System.out.println(armManagers.tenantId());
    System.out.println(armManagers.clientSecret());
    return kubeConfig;
  }
}
