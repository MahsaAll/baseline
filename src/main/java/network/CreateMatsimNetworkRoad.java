package network;

import com.google.common.collect.Sets;
import gis.GpkgReader;
import org.apache.log4j.Logger;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.opengis.feature.simple.SimpleFeature;
import resources.Properties;
import resources.Resources;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.matsim.api.core.v01.TransportMode.*;

// Script to create a MATSim Road network .xml file using the Edges and Nodes from JIBE WP2

public class CreateMatsimNetworkRoad {

    private final static Logger log = Logger.getLogger(CreateMatsimNetworkRoad.class);

    public static void main(String[] args) {

        if (args.length != 1) {
            throw new RuntimeException("Program requires 1 argument: Properties file");
        }

        Resources.initializeResources(args[0]);

        final String networkFile = Resources.instance.getString(Properties.MATSIM_ROAD_NETWORK);

        // Read nodes and edges
        Map<Integer, SimpleFeature> nodes = GpkgReader.readNodes();
        Map<Integer, SimpleFeature> edges = GpkgReader.readEdges();

        // MATSim setup
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        Network net = scenario.getNetwork();
        NetworkFactory fac = net.getFactory();

        // Create network nodes
        nodes.forEach((id,node) -> addNodeToNetwork(id,node,net,fac));

        // Create network links
        edges.forEach((id,edge) -> addLinkToNetwork(id,edge,net,fac));

        // Write network
        new NetworkWriter(net).write(networkFile);
    }

    private static void addNodeToNetwork(int nodeID, SimpleFeature point, Network net, NetworkFactory fac) {
        int z = (int) point.getAttribute("z_coor");
        Point p = (Point) point.getAttributes().get(0);
        Node node = fac.createNode(Id.createNodeId(nodeID), new Coord(p.getX(),p.getY(),z));

        // Cycle crossing type
        String cycleCrossing = (String) point.getAttribute("cyc_cros");
        if(cycleCrossing == null) {
            cycleCrossing = "null";
        }
        node.getAttributes().putAttribute("bikeCrossing",cycleCrossing);

        // Walk crossing type
        String pedCrossing = (String) point.getAttribute("ped_cros");
        if(pedCrossing == null) {
            pedCrossing = "null";
        }
        node.getAttributes().putAttribute("walkCrossing",pedCrossing);

        net.addNode(node);
    }

    private static void addLinkToNetwork(int edgeID, SimpleFeature edge, Network net, NetworkFactory fac) {
        double length = (double) edge.getAttribute("length");
        int origNodeId =  (int) edge.getAttribute("from");
        int destNodeId = (int) edge.getAttribute("to");
        String linkModes = (String) edge.getAttribute("modes");

        if(origNodeId != destNodeId && !List.of("bus","tram","train").contains(linkModes)) {
            Node origNode = net.getNodes().get(Id.createNodeId(origNodeId));
            Node destNode = net.getNodes().get(Id.createNodeId(destNodeId));

            Link l1 = fac.createLink(Id.createLinkId(edgeID + "out"), origNode, destNode);
            Link l2 = fac.createLink(Id.createLinkId(edgeID + "rtn"), destNode, origNode);

            // Original Edge ID
            l1.getAttributes().putAttribute("edgeID",edgeID);
            l2.getAttributes().putAttribute("edgeID",edgeID);

            // OSM ID
            putIntegerAttribute(l1,edge,"osm_id","osmID",-1);
            putIntegerAttribute(l2,edge,"osm_id","osmID",-1);

            // Name
            String name = (String) edge.getAttribute("name");
            if(name == null) name = "";
            l1.getAttributes().putAttribute("name",name);
            l2.getAttributes().putAttribute("name",name);

            // Out or return
            l1.getAttributes().putAttribute("fwd",true);
            l2.getAttributes().putAttribute("fwd",false);

            // Length
            l1.setLength(length);
            l2.setLength(length);

            // Freespeed
            double freespeed = (double) edge.getAttribute("freespeed");
            l1.setFreespeed(freespeed);
            l2.setFreespeed(freespeed);

            // Urban or rural
            boolean urban = (boolean) edge.getAttribute("urban");
            l1.getAttributes().putAttribute("urban",urban);
            l2.getAttributes().putAttribute("urban",urban);

            // CYCLING INFRASTRUCTURE
            // Cycle lane
            l1.getAttributes().putAttribute("cycleway",Objects.requireNonNullElse(edge.getAttribute("cyclwy_f"),"null"));
            l2.getAttributes().putAttribute("cycleway",Objects.requireNonNullElse(edge.getAttribute("cyclwy_b"),"null"));

            // OSM Cycle lane type
            String cycleosm = (String) edge.getAttribute("cyclesm");
            if(cycleosm == null) {
                cycleosm = "null";
            }
            l1.getAttributes().putAttribute("cycleosm",cycleosm);
            l2.getAttributes().putAttribute("cycleosm",cycleosm);

            // ROAD TYPE
            // Type
            String highway = (String) edge.getAttribute("highway");
            l1.getAttributes().putAttribute("type",edge.getAttribute("highway"));
            l2.getAttributes().putAttribute("type",edge.getAttribute("highway"));

            // Roadtyp attribute
            String roadType = (String) edge.getAttribute("roadtyp");
            if(roadType == null) {
                roadType = highway;
            }

            // Is the road a motorway?
            boolean motorway = roadType.contains("motorway");
            l1.getAttributes().putAttribute("motorway",motorway);
            l2.getAttributes().putAttribute("motorway",motorway);

            // Is the road a trunk road?
            boolean trunk = motorway || roadType.contains("Trunk") || roadType.contains("trunk");
            l1.getAttributes().putAttribute("trunk",trunk);
            l2.getAttributes().putAttribute("trunk",trunk);

            // Is the road a primary road?
            boolean primary = trunk || roadType.contains("Main") || roadType.contains("primary");
            l1.getAttributes().putAttribute("primary",primary);
            l2.getAttributes().putAttribute("primary",primary);

            // ALLOWED MODES
            Set<String> allowedModesOut = Sets.newHashSet(linkModes.split(","));
            if (allowedModesOut.contains("walk")) {
                allowedModesOut.add("netwalk");  // Add 'netwalk' only if 'walk' is in the allowed modes 
            }
            l1.setAllowedModes(allowedModesOut);

            // If allows walk but not bike, add bike but specify must dismount
            boolean walkNotBike = allowedModesOut.contains(walk) && !allowedModesOut.contains(bike);
            if(walkNotBike) allowedModesOut.add(bike);

            // One way details
            boolean oneWay = ((String) edge.getAttribute("onwysmm")).startsWith("One Way") || Boolean.TRUE.equals(edge.getAttribute("is_oneway"));

            // Allowed modes on return link
            Set<String> allowedModesRtn = new HashSet<>(allowedModesOut);
            if(oneWay) {
                allowedModesRtn.remove(car);
                allowedModesRtn.remove(truck);
            }

            // Set allowed modes
            l1.setAllowedModes(allowedModesOut);
            l2.setAllowedModes(allowedModesRtn);

            // Do cyclists have to dismount?
            boolean dismount = walkNotBike || roadType.contains("Cycling Forbidden") || cycleosm.equals("dismount");
            l1.getAttributes().putAttribute("dismount",dismount);
            l2.getAttributes().putAttribute("dismount",dismount || oneWay);

            // Are cars allowed on this link? (necessary for mode-specific filtered networks)
            boolean allowsCarOut = allowedModesOut.contains(car);
            boolean allowsCarRtn = allowedModesRtn.contains(car);
            boolean allowsCar = allowsCarOut || allowsCarRtn;

            // Are cars allowed in either direction?
            l1.getAttributes().putAttribute("allowsCar",allowsCar);
            l2.getAttributes().putAttribute("allowsCar",allowsCar);

            // Are cars allowed in the forward direction?
            l1.getAttributes().putAttribute("allowsCarFwd", allowsCarOut);
            l2.getAttributes().putAttribute("allowsCarFwd", allowsCarRtn);

            // Width
            double widthOut = (double) edge.getAttribute("avg_wdt_mp");
            double widthRtn = 0.;

            if(allowsCarRtn || (!allowsCarOut && !allowedModesRtn.isEmpty())) {
                widthOut /= 2.;
                widthRtn = widthOut;
            }

            l1.getAttributes().putAttribute("width",widthOut);
            l2.getAttributes().putAttribute("width",widthRtn);

            // Width and number of lanes
            double lanes = Math.min((int) edge.getAttribute("permlanes"),8);
            double lanesOut = allowsCarOut ? lanes : 1.;
            double lanesRtn = allowsCarRtn ? lanes : 1.;
            l1.setNumberOfLanes(lanesOut);
            l2.setNumberOfLanes(lanesRtn);

            // Capacity
            double capacity = (double) edge.getAttribute("capacity");
            // Adjust capacity of short links
            double adjustedCapacity = (length < 100) ? capacity * 2 : capacity;

            l1.setCapacity(allowsCarOut ? adjustedCapacity : 0.);
            l2.setCapacity(allowsCarRtn ? adjustedCapacity : 0.);


            // Speed limit (miles per hour)
            Object speedLimitObj = edge.getAttribute("maxspeed");
            double speedLimit;
            if(speedLimitObj instanceof Double) {
                speedLimit = (double) speedLimitObj;
            } else if (speedLimitObj instanceof Integer) {
                speedLimit = ((Integer) speedLimitObj).doubleValue();
            } else {
                throw new RuntimeException("maxspeed attribute must be stored as integer or double!");
            }

            l1.getAttributes().putAttribute("speedLimitMPH",speedLimit);
            l2.getAttributes().putAttribute("speedLimitMPH",speedLimit);

            // Surface
            String surface = (String) edge.getAttribute("surface");
            if(surface == null) {
                surface = "null";
            }
            l1.getAttributes().putAttribute("surface",surface);
            l2.getAttributes().putAttribute("surface",surface);

            // Car speed
            Double veh85percSpeedKPH = (Double) edge.getAttribute("spedKPH");
            if(veh85percSpeedKPH == null || !allowsCar) veh85percSpeedKPH = Double.NaN;
            l1.getAttributes().putAttribute("veh85percSpeedKPH",veh85percSpeedKPH);
            l2.getAttributes().putAttribute("veh85percSpeedKPH",veh85percSpeedKPH);

            // Add links to network
            net.addLink(l1);
            if(!l2.getAllowedModes().isEmpty()) {
                net.addLink(l2);
            }
        }
    }


    private static void putDoubleAttribute(Link link, SimpleFeature edge, String name, String matsimName, double valueIfNull) {
        Double attr = (Double) edge.getAttribute(name);
        if(attr == null) {
            attr = valueIfNull;
        }
        link.getAttributes().putAttribute(matsimName,attr);
    }

    private static void putIntegerAttribute(Link link, SimpleFeature edge, String name, String matsimName, int valueIfNull) {
        Integer attr = (Integer) edge.getAttribute(name);
        if(attr == null) {
            attr = valueIfNull;
        }
        link.getAttributes().putAttribute(matsimName,attr);
    }

}
