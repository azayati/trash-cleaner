package org.exoplatform.addons.trashCleaner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionIterator;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.cms.documents.TrashService;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.core.ManageableRepository;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.rest.resource.ResourceContainer;

@Path("computeTrashSize")
public class ComputeTrashSizeService implements ResourceContainer {

  private static final Log LOG = ExoLogger.getLogger(ComputeTrashSizeService.class);
  int nbFiles;
  long size;

  long versionHistorySize;

  RepositoryService      repositoryService;
  SessionProviderService sessionProviderService;

  public ComputeTrashSizeService(RepositoryService repositoryService, SessionProviderService sessionProviderService) {
    this.repositoryService = repositoryService;
    this.sessionProviderService = sessionProviderService;
  }


  @GET
  @RolesAllowed("administrators")
  public Response computeTrashSize() {
    LOG.info("Compute Trash size.");
    TrashService trashService = ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(TrashService.class);
    nbFiles = 0;
    size = 0;
    versionHistorySize=0;
    Node trashNode = trashService.getTrashHomeNode();

    try {

      if (trashNode.hasNodes()){
        computeSubFolderSize(trashNode);
      }
    } catch (RepositoryException ex){
      LOG.info("Failed to get child nodes", ex);
    }
    String result = "Compute Trash size successfully. There are "+nbFiles+" files in trash, with a size of "+humanReadableByteCountBin(size)+". Theses files are related to a size of "+humanReadableByteCountBin(versionHistorySize)+" in versions history!";
    LOG.info(result);
    return Response.ok(result).build();

  }

  private void computeSubFolderSize(Node node) throws RepositoryException {
    NodeIterator childNodes = node.getNodes();

    while (childNodes.hasNext()){
      Node currentNode = (Node) childNodes.next();
      if (currentNode.isNodeType("nt:file")) {
        nbFiles++;
        Node content=currentNode.getNode("jcr:content");
        size += getContentSize(content);
        if (currentNode.isNodeType("mix:versionable")) {
          versionHistorySize += computeVersionHistorySizeForNode(currentNode);
        }
      } else if (currentNode.isNodeType("nt:folder") || currentNode.isNodeType("nt:unstructured")) {
        computeSubFolderSize(currentNode);
      }
    }
  }

  private long computeVersionHistorySizeForNode(Node currentNode) throws RepositoryException {
    List<Version> versions = getFileVersions(currentNode.getUUID());
    return versions.stream().reduce(0, (subtotal, element) -> {
      try {
        return subtotal + getContentSize(element.getNode("jcr:frozenNode").getNode("jcr:content"));
      } catch (RepositoryException e) {
        try {
          LOG.error("Unable to read version {} size",element.getPath(),e);
        } catch (RepositoryException ex) {
          //ignore it
        }
        return subtotal;
      }
    }, Integer::sum);
  }

  public static String humanReadableByteCountBin(long bytes) {
    long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
    if (absB < 1024) {
      return bytes + " B";
    }
    long value = absB;
    CharacterIterator ci = new StringCharacterIterator("KMGTPE");
    for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
      value >>= 10;
      ci.next();
    }
    value *= Long.signum(bytes);
    return String.format("%.1f %ciB", value / 1024.0, ci.current());
  }


  public List<Version> getFileVersions(String fileNodeId) {
    List<Version> fileVersions = new ArrayList<>();

    try {
      ManageableRepository manageableRepository = repositoryService.getCurrentRepository();
      Session session = this.sessionProviderService.getSystemSessionProvider(null).getSession("collaboration", manageableRepository);
      Node node = session.getNodeByUUID(fileNodeId);
      Version rootVersion = node.getVersionHistory().getRootVersion();
      VersionIterator versionIterator = node.getVersionHistory().getAllVersions();
      while (versionIterator.hasNext()) {
        Version version = versionIterator.nextVersion();
        if (version.getUUID().equals(rootVersion.getUUID())) {
          continue;
        }
        fileVersions.add(version);
      }
    } catch (RepositoryException e) {
      throw new IllegalStateException("Error while getting file versions", e);
    }
    return fileVersions;
  }


  public int getContentSize(Node content) throws RepositoryException {
    try {
      InputStream contentInputStream = content.getProperty("jcr:data").getValue().getStream();
      return readAllBytes(contentInputStream).length;
    } catch (Exception e) {
      LOG.error("Unable to compute size for node {}", content.getPath());
      return 0;
    }
  }
  
  private static byte[] readAllBytes(InputStream inputStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int bytesRead;
    while ((bytesRead = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
    }
    return outputStream.toByteArray();
  }
}
