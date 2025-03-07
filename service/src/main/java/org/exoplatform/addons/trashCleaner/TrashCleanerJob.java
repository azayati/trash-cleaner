package org.exoplatform.addons.trashCleaner;

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.cms.actions.ActionServiceContainer;
import org.exoplatform.services.cms.documents.TrashService;
import org.exoplatform.services.cms.relations.RelationsService;
import org.exoplatform.services.cms.thumbnail.ThumbnailService;
import org.exoplatform.services.jcr.RepositoryService;

import org.exoplatform.ecm.webui.utils.Utils;
import org.exoplatform.ecm.webui.utils.PermissionUtil;
import org.exoplatform.services.jcr.core.ManageableRepository;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.jcr.impl.core.NodeImpl;
import org.exoplatform.services.jcr.impl.core.SessionImpl;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.DisallowConcurrentExecution;

import javax.jcr.*;
import javax.jcr.nodetype.ConstraintViolationException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Created by Romain Dénarié (romain.denarie@exoplatform.com) on 22/01/16.
 */
@DisallowConcurrentExecution
public class TrashCleanerJob implements Job {

  private static final Log LOG = ExoLogger.getLogger(TrashCleanerJob.class);

  public TrashCleanerJob() {
  }

  public void execute(JobExecutionContext context) throws JobExecutionException {
    String timeLimit = System.getProperty("trashcleaner.lifetime");
    if (timeLimit == null)
      timeLimit = "30";
    LOG.info("Start TrashCleanerJob, delete nodes in trash older than " + timeLimit + " days.");
    TrashService trashService = ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(TrashService.class);
    int deletedNode = 0;
    Node trashNode = trashService.getTrashHomeNode();

    try {

      if (trashNode.hasNodes()) {
        NodeIterator childNodes = trashNode.getNodes();
        long size = childNodes.getSize();
        int current = 0;

        while (childNodes.hasNext()) {
          Node currentNode = (Node) childNodes.next();
          if (!currentNode.getSession().isLive()) {
            currentNode.getSession().refresh(false);
          }
          try {
            current++;
            if (current % 50 == 0) {
              LOG.info("Checking node " + currentNode.getName() + " node from Trash (" + current + "/" + size + ")");
            } else {
              LOG.debug("Checking node " + currentNode.getName() + " node from Trash (" + current + "/" + size + ")");
            }
            if (currentNode.getName().equals("exo:actions") && currentNode.hasNode("trashFolder")) {
              continue;
            }
            if (currentNode.hasProperty("exo:lastModifiedDate")) {
              long dateCreated = currentNode.getProperty("exo:lastModifiedDate").getDate().getTimeInMillis();
              if ((Calendar.getInstance().getTimeInMillis() - dateCreated > Long.parseLong(timeLimit) * 24 * 60 * 60 * 1000)
                  && (currentNode.isNodeType("exo:restoreLocation"))) {
                recursiveDelete(currentNode);
//                deleteNode(currentNode);
                deletedNode++;
              }
            } else {
              recursiveDelete(currentNode);
//              deleteNode(currentNode);
              deletedNode++;
            }
          } catch (Exception ex) {
            LOG.error("Error while removing " + currentNode.getName() + " node from Trash", ex);
          }
        }
      }
    } catch (RepositoryException ex) {
      LOG.error("Failed to get child nodes", ex);
    }
    LOG.info("Empty Trash folder successfully! " + deletedNode + " nodes deleted");
  }

  public void recursiveDelete(Node node) throws Exception {
    if (node.isNodeType("nt:folder") || node.isNodeType("nt:unstructured")) {
      NodeIterator children = node.getNodes();
      while (children.hasNext()) {
        Node child = children.nextNode();
        recursiveDelete(child);
      }
    }
    deleteNode(node);
  }

  public void deleteNode(Node node) throws Exception {
    ActionServiceContainer actionService = ExoContainerContext.getCurrentContainer()
                                                              .getComponentInstanceOfType(ActionServiceContainer.class);
    ThumbnailService thumbnailService = ExoContainerContext.getCurrentContainer()
                                                           .getComponentInstanceOfType(ThumbnailService.class);
    RepositoryService repoService = ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(RepositoryService.class);
    SessionProvider sessionProviderForDeleteNode = SessionProvider.createSystemProvider();
    Session sessionForDeleteNode =sessionProviderForDeleteNode.getSession("collaboration",repoService.getDefaultRepository());
    LOG.debug("Try to delete node {}",node.getPath());
    try {
      Node nodeToDelete = readNodeWithNewSession(node,sessionForDeleteNode);
      try {
        removeReferences(nodeToDelete);
      } catch (Exception ex) {
        LOG.error("An error occurs while removing relations for node {}", nodeToDelete.getPath(), ex);
      }

      try {
        actionService.removeAction(nodeToDelete, repoService.getCurrentRepository().getConfiguration().getName());
      } catch (Exception ex) {
        LOG.error("An error occurs while removing actions related to node {} ", nodeToDelete.getPath(), ex);
      }
      try {
        thumbnailService.processRemoveThumbnail(nodeToDelete);
      } catch (Exception ex) {
        LOG.error("An error occurs while removing thumbnail for node {} ", nodeToDelete.getPath(), ex);
      }
      try {
        if (PermissionUtil.canRemoveNode(nodeToDelete) && nodeToDelete.isNodeType(Utils.EXO_AUDITABLE)) {
          removeAuditForNode(nodeToDelete, repoService.getCurrentRepository());
        }
      } catch (Exception ex) {
        LOG.error("An error occurs while removing audit for node {}", nodeToDelete.getPath(), ex);
      }
      nodeToDelete.remove();
      nodeToDelete.getSession().save();
      LOG.debug("Node " + nodeToDelete.getPath() + " deleted");
    } catch (ReferentialIntegrityException ref) {
      if (!fixReferentialIntegrityException(node)) {
        LOG.error("ReferentialIntegrityException when removing " + node.getName() + " node from Trash", ref);
        //log nothing if the fix success to deleteNode
      }
    } catch (ConstraintViolationException cons) {
      LOG.error("ConstraintViolationException when removing " + node.getName() + " node from Trash", cons);
    } catch (Exception ex) {
      LOG.error("Error while removing " + node.getName() + " node from Trash", ex);
    } finally {
      sessionForDeleteNode.logout();
      sessionProviderForDeleteNode.close();
    }
  }

  private boolean fixReferentialIntegrityException(Node node) {
    String name="";

    //This node have a version in versionHistory,
    // which is set as liveRevision of another node which was copy pasted from this node
    //we need to
    //1) find this node
    //2) clean his publication

    try {
      Session session = node.getSession();
      session.refresh(false);
      name=node.getName();
      boolean shouldDeleteNode = false;

      String liveRevision = node.getProperty("publication:liveRevision").getValue().getString();
      Node versionHistory = session.getNodeByUUID(liveRevision).getParent();
      for (NodeIterator it = versionHistory.getNodes(); it.hasNext(); ) {
        Node child = it.nextNode();
        long nbRef = child.getReferences().getSize();
        LOG.info("Version history child node {} have {} references",child.getPath(),nbRef);
        for (PropertyIterator iter = child.getReferences(); iter.hasNext(); ) {
          // if there is a reference, move it
          String relationPath = iter.nextProperty().getPath();
          LOG.info("Node " + child.getPath() + " is referenced by " + relationPath);
          if (!relationPath.startsWith(node.getPath())) {
            Node relation = node.getSession().getItem(relationPath).getParent();
            cleanPublication(relation);
            shouldDeleteNode=true;
          }
        }
      }

      if (shouldDeleteNode) {
        deleteNode(node);
        return true;
      }

    } catch (Exception e) {
      LOG.error("Error while removing (without the version history) " + name + " node from Trash", e);
    }
    return false;


  }

  private void cleanPublication(Node relation) {
    String name="";
    try {
      name = relation.getName();
      relation.setProperty("publication:liveRevision", (javax.jcr.Value) null);
      relation.setProperty("publication:currentState", "published");
      relation.save();

      LOG.info("Publication cleaned on node {}, to fix integrity problem.",name);
    } catch (Exception e) {
      LOG.error("Unable to clean publication for node {}",name,e);
    }
  }

  private Node readNodeWithNewSession(Node node, Session sessionForDeleteNode) throws RepositoryException {
    String idf = ((NodeImpl)node).getIdentifier();
    return ((SessionImpl)sessionForDeleteNode).getNodeByIdentifier(idf);
  }

  private void removeReferences(Node node) throws Exception {
    RelationsService relationService =ExoContainerContext.getCurrentContainer()
                                                         .getComponentInstanceOfType(RelationsService.class);


    PropertyIterator iter = node.getReferences();
    if (iter.hasNext()) {
      // if there is a reference, move it
      String relationPath = iter.nextProperty().getPath();
      LOG.debug("Node " + node.getPath() + " is referenced by " + relationPath + ", remove the referenec");

      Item relation = node.getSession().getItem(relationPath);
      Node sourceRelationNode = relation.getParent();

      relationService.removeRelation(sourceRelationNode,node.getPath());
    }

    NodeIterator children = node.getNodes();
    while (children.hasNext()) {
      Node child = children.nextNode();
      removeReferences(child);
    }

  }

  private void removeAuditForNode(Node node, ManageableRepository repository) throws Exception {
    Session session = SessionProvider.createSystemProvider().getSession(node.getSession().getWorkspace().getName(), repository);
    if (session.getRootNode().hasNode("exo:audit") && session.getRootNode().getNode("exo:audit").hasNode(node.getUUID())) {
      session.getRootNode().getNode("exo:audit").getNode(node.getUUID()).remove();
      session.save();
    }
  }

}
