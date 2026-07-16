package org.eqasim.bavaria;

import jakarta.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Schreibt das Output-Netzwerk nach Simulationsende als WGS84-GeoJSON.
 * Nötig weil CreateGeoJsonNetwork das eqasim-eigene "Atlantis"-CRS nicht kennt.
 * Nur Hauptstraßen (car-Modus + freespeed >= 50 km/h) werden einbezogen,
 * damit die Datei handhabbar bleibt.
 */
public class MoCoNetworkGeoJsonWriter implements ShutdownListener {

    private static final Logger log = LogManager.getLogger(MoCoNetworkGeoJsonWriter.class);

    // ~13.9 m/s = 50 km/h – filtert Wohnstraßen heraus
    private static final double MIN_FREESPEED_MS = 13.9;

    @Inject
    private Network network;

    @Inject
    private Config config;

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        String outputDir = config.controller().getOutputDirectory();
        Path outputFile = Paths.get(outputDir, "network_wgs84.geojson");

        // Das Bavarian eqasim-Szenario speichert Koordinaten in EPSG:25832 (UTM 32N),
        // obwohl die Config "Atlantis" sagt. Wir konvertieren daher direkt von EPSG:25832.
        String sourceCrs = "EPSG:25832";
        log.info("MoCoNetworkGeoJsonWriter: {} → WGS84, Ausgabe: {}", sourceCrs, outputFile);

        CoordinateTransformation ct;
        try {
            ct = TransformationFactory.getCoordinateTransformation(sourceCrs, TransformationFactory.WGS84);
        } catch (Exception e) {
            log.error("CRS-Transformation fehlgeschlagen – network_wgs84.geojson wird nicht erzeugt: {}", e.getMessage());
            return;
        }

        int written = 0;
        int skipped = 0;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {
            writer.write("{\"type\":\"FeatureCollection\",\"features\":[");

            boolean first = true;
            for (Link link : network.getLinks().values()) {
                // Nur Car-Links mit Freispeed >= 50 km/h (Hauptstraßennetz)
                if (!link.getAllowedModes().contains("car")) { skipped++; continue; }
                if (link.getFreespeed() < MIN_FREESPEED_MS)  { skipped++; continue; }

                Coord from = ct.transform(link.getFromNode().getCoord());
                Coord to   = ct.transform(link.getToNode().getCoord());

                if (!first) writer.write(",");
                first = false;

                writer.write("{\"type\":\"Feature\",\"id\":\"");
                writer.write(link.getId().toString());
                writer.write("\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[");
                writer.write(from.getX() + "," + from.getY());
                writer.write("],[");
                writer.write(to.getX() + "," + to.getY());
                writer.write("]]}");
                writer.write(",\"properties\":{\"id\":\"");
                writer.write(link.getId().toString());
                writer.write("\",\"length\":");
                writer.write(String.valueOf((long) link.getLength()));
                writer.write(",\"freespeed_kmh\":");
                writer.write(String.format("%.0f", link.getFreespeed() * 3.6));
                writer.write("}}");
                written++;
            }

            writer.write("]}");
        } catch (IOException e) {
            throw new RuntimeException("Fehler beim Schreiben von network_wgs84.geojson", e);
        }

        log.info("MoCoNetworkGeoJsonWriter: {} Links geschrieben, {} übersprungen → {}",
                written, skipped, outputFile.getFileName());
    }
}
