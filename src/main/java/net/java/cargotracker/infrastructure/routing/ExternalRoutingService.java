package net.java.cargotracker.infrastructure.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Stateless;
import javax.enterprise.concurrent.ContextService;
import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import net.java.cargotracker.application.internal.ApplicationInfo;
import javax.ws.rs.client.*;
import javax.ws.rs.core.*;
import net.java.cargotracker.application.util.JsonMoxyConfigurationContextResolver;
import net.java.cargotracker.domain.model.cargo.Itinerary;
import net.java.cargotracker.domain.model.cargo.Leg;
import net.java.cargotracker.domain.model.cargo.RouteSpecification;
import net.java.cargotracker.domain.model.location.LocationRepository;
import net.java.cargotracker.domain.model.location.UnLocode;
import net.java.cargotracker.domain.model.voyage.VoyageNumber;
import net.java.cargotracker.domain.model.voyage.VoyageRepository;
import net.java.cargotracker.domain.service.RoutingService;
import net.java.pathfinder.api.TransitEdge;
import net.java.pathfinder.api.TransitPath;
import org.glassfish.jersey.moxy.json.MoxyJsonFeature;

/**
 * Our end of the routing service. This is basically a data model translation
 * layer between our domain model and the API put forward by the routing team,
 * which operates in a different context from us.
 *
 */
@Stateless
public class ExternalRoutingService implements RoutingService {

    @Resource(lookup = "java:app/configuration/GraphTraversalUrl")
    private String graphTraversalUrl;
    // TODO Can I use injection?
    private final Client jaxrsClient = ClientBuilder.newClient();
    private WebTarget graphTraversalResource;
    @Inject
    private LocationRepository locationRepository;
    @Inject
    private VoyageRepository voyageRepository;
    // TODO Use injection instead?
    private static final Logger log = Logger.getLogger(
            ExternalRoutingService.class.getName());
    @Inject
    private ApplicationInfo appInfo;
    @Resource
    ContextService contextService;

    @PostConstruct
    public void init() {
        if (appInfo.getServletContext() != null) {
            String urlPropertyName = "reactivejavaee.graphTraversalUrl." + appInfo.getServletContext().getContextPath().substring(1);
            graphTraversalResource = jaxrsClient.target(System.getProperty(urlPropertyName, graphTraversalUrl));
        } else {
            graphTraversalResource = jaxrsClient.target(graphTraversalUrl);
        }
        graphTraversalResource.register(new MoxyJsonFeature()).register(
                new JsonMoxyConfigurationContextResolver());
    }

    @Override
    public List<Itinerary> fetchRoutesForSpecification(
            RouteSpecification routeSpecification) {
        // The RouteSpecification is picked apart and adapted to the external API.
        String origin = routeSpecification.getOrigin().getUnLocode().getIdString();
        String destination = routeSpecification.getDestination().getUnLocode()
                .getIdString();

        CompletableFuture<List<Itinerary>> futureResult = new CompletableFuture<>();
        graphTraversalResource
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .async()
                .get(new InvocationCallback<List<TransitPath>>() {
                    
                    Consumer<List<TransitPath>> onCompleted = contextService.createContextualProxy(new Consumer<List<TransitPath>>() {
                        @Override
                        public void accept(List<TransitPath> transitPaths) {
                            // The returned result is then translated back into our domain model.
                            List<Itinerary> itineraries = new ArrayList<>();

                            for (TransitPath transitPath : transitPaths) {
                                Itinerary itinerary = toItinerary(transitPath);
                                // Use the specification to safe-guard against invalid itineraries
                                if (routeSpecification.isSatisfiedBy(itinerary)) {
                                    itineraries.add(itinerary);
                                } else {
                                    log.log(Level.FINE,
                                            "Received itinerary that did not satisfy the route specification");
                                }
                            }

                            futureResult.complete(itineraries);
                        }

                    }, Consumer.class);

                    @Override
                    public void completed(List<TransitPath> transitPaths) {
                        onCompleted.accept(transitPaths);
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        futureResult.completeExceptionally(throwable);
                    }

                });

        try {
            return futureResult.get(300, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }

    }

    private Itinerary toItinerary(TransitPath transitPath) {
        List<Leg> legs = new ArrayList<>(transitPath.getTransitEdges().size());
        for (TransitEdge edge : transitPath.getTransitEdges()) {
            legs.add(toLeg(edge));
        }
        return new Itinerary(legs);
    }

    private Leg toLeg(TransitEdge edge) {
        return new Leg(
                voyageRepository.find(new VoyageNumber(edge.getVoyageNumber())),
                locationRepository.find(new UnLocode(edge.getFromUnLocode())),
                locationRepository.find(new UnLocode(edge.getToUnLocode())),
                edge.getFromDate(), edge.getToDate());
    }
}
