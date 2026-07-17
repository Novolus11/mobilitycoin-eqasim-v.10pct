package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Employment (EMPLOY) allocation.
 *
 * Score: S = employment_status (feste Werte, keine Normalisierung)
 *
 *   employed=false                          → S = 0.0  (nicht erwerbstätig)
 *   employed=true  AND homeoffice=true      → S = 0.5  (Homeoffice)
 *   employed=true  AND homeoffice=false     → S = 1.0  (Pendler)
 *
 * Agenten ohne Eintrag in der params-CSV → S = 0.
 *
 * Coins je Agent: coins_i = totalCoins × (S_i / Σ S_i)
 */
public class EmployAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(EmployAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;

    public EmployAllocationCalculator(Map<String, AgentParametersPrecomputer.AgentParams> agentParams) {
        this.agentParams = agentParams;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // Pass 1: Score je Agent per Fallunterscheidung bestimmen
        // Quelle: ap.employed (boolean) und ap.homeoffice (String "true"/"false")
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);

            double score = 0.0;
            if (ap != null) {
                if (!ap.employed) {
                    score = 0.0;  // nicht erwerbstätig
                } else if ("true".equalsIgnoreCase(ap.homeoffice)) {
                    score = 0.5;  // erwerbstätig, Homeoffice
                } else {
                    score = 1.0;  // erwerbstätig, Pendler
                }
            }

            scores.put(person.getId(), score);
            totalScore += score;
        }

        // Pass 2: Proportional zu Gesamtscore verteilen
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("EMPLOY: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
            double coinsPerPerson = totalCoins / population.getPersons().size();
            for (Person person : population.getPersons().values()) {
                allocations.put(person.getId(), coinsPerPerson);
            }
        } else {
            for (Person person : population.getPersons().values()) {
                double s = scores.getOrDefault(person.getId(), 0.0);
                allocations.put(person.getId(), totalCoins * (s / totalScore));
            }
        }

        logStats(allocations);
        return allocations;
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        logger.info("EMPLOY Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return "Employment (EMPLOY): S=0 (nicht erw.) / 0.5 (Homeoffice) / 1.0 (Pendler)";
    }
}
