package org.exoplatform.addons.trashCleaner;

import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.rest.resource.ResourceContainer;
import org.quartz.JobExecutionException;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("/trashcleaner")
public class TrashCleanerRestService implements ResourceContainer {
  private static final Log                 LOG = ExoLogger.getLogger(TrashCleanerRestService.class);

  public TrashCleanerRestService() {

  }

  @GET
  @RolesAllowed("administrators")
  public Response launchTrashCleanerJob() {
    ExoContainer context = ExoContainerContext.getCurrentContainer();
    try {
      new Thread(() -> {
        TrashCleanerJob trashCleanerJob = new TrashCleanerJob();
        ExoContainerContext.setCurrentContainer(context);
        try {
          trashCleanerJob.execute(null);
        } catch (JobExecutionException e) {
          throw new RuntimeException(e);
        }
      }).start();
      return Response.ok().build();
    } catch (Exception e) {
      LOG.error("Error starting job TrashCleanerJob",e);
      return Response.serverError().build();

    }
  }
}
