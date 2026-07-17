package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Accessibility (ACCESS) allocation.
 *
 * Score: S = 1 - normalize(pt_average_raptor, min, max)
 *
 * pt_average_raptor wird min-max-normalisiert und invertiert:
 * hoher Raptor-Wert (schlechter ÖPNV-Zugang) → niedriger Score → weniger Coins
 * niedriger Raptor-Wert (guter ÖPNV-Zugang)   → hoher Score  → mehr Coins
 *
 * Agenten ohne pt_average_raptor-Eintrag (null) → Score = 0.
 *
 * Coins je Agent: coins_i = totalCoins × (S_i / Σ S_i)
 */
public class AccessAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(AccessAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;

    public AccessAllocationCalculator(Map<String, AgentParametersPrecomputer.AgentParams> agentParams) {
        this.agentParams = agentParams;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // -------------------------------------------------------------------------
        // Pass 1: pt_average_raptor-Werte einlesen und Min/Max bestimmen
        //
        // Quelle: ap.ptAverageRaptor (Integer, nullable)
        // pt_average_raptor ist der ÖPNV-Erreichbarkeitswert nach dem RAPTOR-Algorithmus –
        // je kleiner der Wert, desto besser die ÖPNV-Anbindung.
        // Agenten mit null (kein Raptor-Eintrag in CSV) werden übersprungen;
        // in Pass 2 bekommen sie score=0.0.
        // -------------------------------------------------------------------------
        Map<String, Double> rawPt = new HashMap<>();
        double minPt = Double.MAX_VALUE;
        double maxPt = -Double.MAX_VALUE;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);
            if (ap == null || ap.ptAverageRaptor == null) continue;

            double pt = (double) ap.ptAverageRaptor;
            rawPt.put(pid, pt);
            if (pt < minPt) minPt = pt;
            if (pt > maxPt) maxPt = pt;
        }

        if (rawPt.isEmpty()) {
            logger.warn("ACCESS: pt_average_raptor ist für alle Agenten null – Fallback auf gleichmäßige Verteilung.");
            Map<Id<Person>, Double> allocations = new HashMap<>();
            double coinsPerPerson = totalCoins / population.getPersons().size();
            for (Person person : population.getPersons().values()) {
                allocations.put(person.getId(), coinsPerPerson);
            }
            return allocations;
        }

        // Pass 2: Normalisieren, invertieren → Score
        // S = 1 - (pt - min) / (max - min)
        // → niedrigster pt-Wert bekommt S=1.0, höchster bekommt S=0.0
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            Double pt = rawPt.get(pid);
            double score = 0.0;
            if (pt != null) {
                double norm = (maxPt > minPt) ? (pt - minPt) / (maxPt - minPt) : 0.5;
                score = 1.0 - norm;
            }
            // kein pt_average_raptor-Eintrag → score bleibt 0.0
            scores.put(person.getId(), score);
            totalScore += score;
        }

        // Pass 3: Coins proportional zum Score verteilen
        // Formel: coins_i = totalCoins × (S_i / Σ S_i)
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("ACCESS: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
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

    private double normalize(double value, double min, double max) {
        if (max <= min) return 0.5;
        return (value - min) / (max - min);
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        logger.info("ACCESS Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return "Accessibility (ACCESS): S = 1 - normalize(pt_average_raptor) – niedrige Erreichbarkeit erhält mehr Coins";
    }
}
