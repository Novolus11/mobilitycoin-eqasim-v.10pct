package org.eqasim.bavaria;

import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.Header;
import org.matsim.simwrapper.Layout;
import org.matsim.simwrapper.viz.Links;

/**
 * SimWrapper Dashboard: zeigt das Output-Netzwerk als GeoJSON-Karte an.
 * Die GeoJSON-Datei wird von MoCoNetworkGeoJsonWriter beim Simulationsende
 * in WGS84 geschrieben (weil CreateGeoJsonNetwork das "Atlantis"-CRS nicht kennt).
 */
public class MoCoNetworkDashboard implements Dashboard {

    @Override
    public void configure(Header header, Layout layout) {
        header.title = "Bavaria MobilityCoin";
        header.description = "Netzwerk-Übersicht";

        layout.row("Netzwerk")
                .el(Links.class, (viz, data) -> {
                    viz.title = "Output-Netzwerk";
                    viz.network = "network_wgs84.geojson";
                });
    }
}
