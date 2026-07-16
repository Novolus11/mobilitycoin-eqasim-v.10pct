package org.eqasim.bavaria;

import java.util.Collections;
import java.util.Set;

import com.google.inject.multibindings.Multibinder;
import org.eqasim.core.simulation.vdf.VDFConfigGroup;
import org.eqasim.core.simulation.vdf.engine.VDFEngineConfigGroup;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.SimWrapperModule;

public class RunSimulation {
	static public void main(String[] args) throws ConfigurationException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("config-path") //
				.allowPrefixes("mode-choice-parameter", "cost-parameter", "moco", "use-vdf", "use-vdf-engine") //
				.build();

		BavariaConfigurator configurator = new BavariaConfigurator(cmd);
		Config config = ConfigUtils.loadConfig(cmd.getOptionStrict("config-path"));
		configurator.updateConfig(config);

		if (cmd.getOption("use-vdf").map(Boolean::parseBoolean).orElse(false)) {
			config.qsim().setFlowCapFactor(1e9);
			config.qsim().setStorageCapFactor(1e9);

			VDFConfigGroup vdfConfig = new VDFConfigGroup();
			config.addModule(vdfConfig);

			vdfConfig.setCapacityFactor(0.5);
			vdfConfig.setModes(Set.of("car", "car_passenger"));

			if (cmd.getOption("use-vdf-engine").map(Boolean::parseBoolean).orElse(false)) {
				VDFEngineConfigGroup engineConfig = new VDFEngineConfigGroup();
				engineConfig.setModes(Set.of("car", "car_passenger"));
				engineConfig.setGenerateNetworkEvents(false);
				config.addModule(engineConfig);

				config.qsim().setMainModes(Collections.emptySet());
			}
		}

		cmd.applyConfiguration(config);
		// VehiclesValidator validation removed - class not available in this version

		Scenario scenario = ScenarioUtils.createScenario(config);
		configurator.configureScenario(scenario);
		ScenarioUtils.loadScenario(scenario);
		configurator.adjustScenario(scenario);

		Controler controller = new Controler(scenario);
		configurator.configureController(controller);

		// SimWrapper: Netzwerk-Dashboard automatisch beim Simulationsende erzeugen
		controller.addOverridingModule(new SimWrapperModule());
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				Multibinder.newSetBinder(binder(), Dashboard.class)
						.addBinding()
						.toInstance(new MoCoNetworkDashboard());
				// GeoJSON mit WGS84-Koordinaten schreiben (Atlantis → WGS84)
				addControlerListenerBinding().to(MoCoNetworkGeoJsonWriter.class);
			}
		});

		controller.run();
	}
}