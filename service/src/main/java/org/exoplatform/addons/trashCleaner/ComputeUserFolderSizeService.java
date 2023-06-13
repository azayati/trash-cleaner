package org.exoplatform.addons.trashCleaner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import javax.annotation.security.RolesAllowed;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserStatus;
import org.exoplatform.services.rest.resource.ResourceContainer;

@Path("computeUserFolderSize")
public class ComputeUserFolderSizeService implements ResourceContainer {
  private static final Log LOG = ExoLogger.getLogger(ComputeUserFolderSizeService.class);

  private SessionProviderService sessionProviderService;
  private RepositoryService repositoryService;
  private OrganizationService organizationService;
  int totalUsersCount;
  public ComputeUserFolderSizeService(SessionProviderService sessionProviderService, RepositoryService repositoryService, OrganizationService organizationService) {
    this.sessionProviderService=sessionProviderService;
    this.repositoryService = repositoryService;
    this.organizationService=organizationService;
  }

  @GET
  @RolesAllowed("administrators")
  public Response computeUserFolderSizeSize(@QueryParam("date") String date) {
    Instant limitDate = Instant.now();
    if (date == null || date.equals("")) {
      limitDate = limitDate.minus(365*2, ChronoUnit.DAYS);
    } else {
      limitDate = Instant.ofEpochMilli(Long.parseLong(date));
    }
    LOG.info("Compute Users Folder size for user not connected since {}", limitDate);
    int totalSize = 0;
    this.totalUsersCount = 0;
    try {

      long startTime = System.currentTimeMillis();

      Session session = sessionProviderService.getSystemSessionProvider(null).getSession("collaboration",repositoryService.getDefaultRepository());

      Node userRootNode = session.getRootNode().getNode("Users");

      totalSize = browserUsersFolders(userRootNode,limitDate);

      String result = "Total size for users not connected since "+limitDate.toString()+" is "+humanReadableByteCountBin(totalSize)+", for a total of "+totalUsersCount+" users, execution time "+(System.currentTimeMillis() - startTime)+" ms";

      LOG.info(result);
      return Response.ok(result).build();

    } catch (Exception e) {
      LOG.error("Error when searching users",e);
      return Response.serverError().build();
    }
  }

  private int browserUsersFolders(Node currentNode, Instant limitDate) {
    NodeIterator iterator = null;
    String path=null;
    int subTotalSize = 0;
    try {
      path=currentNode.getPath();
      iterator = currentNode.getNodes();
    } catch (RepositoryException e) {
      LOG.error("Error when reading child nodes of {}",path,e);
      return subTotalSize;
    }
    long startTime = System.currentTimeMillis();

    while (iterator.hasNext()) {
      Node child = iterator.nextNode();
      String username ="";
      try {
        username = child.getName();
        if (child.isNodeType("exo:userFolder")) {
          //check if user is connected
          User user = getUser(username);
          if (user == null) {
            LOG.info("Folder {} correspond to a not found user. We can delete it", child.getPath());
            subTotalSize += computeSubFolderSize(child);
            this.totalUsersCount++;
          } else if (isConnected(user)) {
            Instant lastLoginTime = user.getLastLoginTime().toInstant();
            if (lastLoginTime.isBefore(limitDate)) {
              LOG.debug("User {} lastLoginTime ({}) is before limitDate ({}), need to compute size", user.getUserName(), user.getLastLoginTime(), limitDate);
              subTotalSize += computeSubFolderSize(child);
              this.totalUsersCount++;
            }
          } else {
            Instant createdDate = user.getCreatedDate().toInstant();
            if (createdDate.isBefore(limitDate)) {
              LOG.debug("User {} never connected and created ({}) before limitDate ({}) , need to compute size", user.getUserName(), user.getCreatedDate(), limitDate);
              subTotalSize += computeSubFolderSize(child);
              this.totalUsersCount++;
            }
          }
          if (this.totalUsersCount % 100 ==0) {
            LOG.info("Progression : {} users folder computed",this.totalUsersCount);
          }
        } else {
          subTotalSize += browserUsersFolders(child, limitDate);
        }
      } catch (RepositoryException e) {
        LOG.error("Error when computing user folder size for user {}",username);
      }
    }

    LOG.debug("Spent {} ms to browse folder {}",System.currentTimeMillis() - startTime, path);
    return subTotalSize;
  }

  private User getUser(String username) {
    RequestLifeCycle.begin(ExoContainerContext.getCurrentContainer());
    try {
      return organizationService.getUserHandler().findUserByName(username,UserStatus.ANY);
    } catch (Exception e) {
      LOG.error("Unable to find user {}",username);
      return null;
    } finally {
      RequestLifeCycle.end();
    }
  }

  private boolean isConnected(User user) {
    return (user.getLastLoginTime() != null && !user.getCreatedDate().equals(user.getLastLoginTime()));
  }

  private int computeSubFolderSize(Node node) throws RepositoryException {
    NodeIterator childNodes = node.getNodes();
    int size = 0;
    while (childNodes.hasNext()){
      Node currentNode = (Node) childNodes.next();
      if (currentNode.isNodeType("nt:file")) {
        Node content=currentNode.getNode("jcr:content");
        size += getContentSize(content);
      } else if (currentNode.isNodeType("nt:folder") || currentNode.isNodeType("nt:unstructured")) {
        computeSubFolderSize(currentNode);
      }
    }
    return size;
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
