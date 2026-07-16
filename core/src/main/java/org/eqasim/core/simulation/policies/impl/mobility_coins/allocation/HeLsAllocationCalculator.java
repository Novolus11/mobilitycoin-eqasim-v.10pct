package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Horizontal Equity Life Stage (HE_LS) allocation.
 *
 * Score: S = W1*V_Employment + W2*V_Age + W3*V_Travel_Distance
 *
 * V-Werte werden per Fallunterscheidung ermittelt und anschließend min-max-normalisiert:
 *
 *   V_Employment (employed + homeoffice):
 *     1 = nicht erwerbstätig (employed=false)
 *     2 = im Homeoffice     (employed=true, homeoffice=true)
 *     3 = Pendler           (employed=true, homeoffice=false)
 *
 *   V_Age (age):
 *     1 = < 18 Jahre
 *     2 = > 65 Jahre
 *     3 = 18–65 Jahre
 *
 *   V_Travel_Distance (travel_distance aus agent_params.csv):
 *     Terzil-basiert über alle Agenten:
 *     1 = unteres Drittel  (≤ 33,33%-Perzentil)
 *     2 = mittleres Drittel
 *     3 = oberes Drittel   (> 66,67%-Perzentil)
 *
 * Coins je Agent: coins_a = totalCoins × (S_a / Σ S_a)
 */
public class HeLsAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(HeLsAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;
    private final double w1, w2, w3;

    public HeLsAllocationCalculator(
            Map<String, AgentParametersPrecomputer.AgentParams> agentParams,
            double w1, double w2, double w3) {
        this.agentParams = agentParams;
        this.w1 = w1; this.w2 = w2; this.w3 = w3;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // --- Terzilgrenzen für V_Travel_Distance bestimmen ---
        List<Double> distances = new ArrayList<>();
        for (Person person : population.getPersons().values()) {
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(person.getId().toString());
            if (ap != null) distances.add(ap.travelDistanceCarCarpPtM);
        }
        Collections.sort(distances);

        double p33 = 0.0, p67 = 0.0;
        if (!distances.isEmpty()) {
            int n     = distances.size();
            // Letzter Index des unteren bzw. mittleren Drittels -> exakt ~33% je Gruppe
            int idx33 = Math.max(0, (int) Math.floor(n / 3.0) - 1);
            int idx67 = Math.max(0, (int) Math.floor(2.0 * n / 3.0) - 1);
            p33 = distances.get(idx33);
            p67 = distances.get(idx67);
        }
        logger.info("HE_LS Travel_Distance Terzile: p33={} m, p67={} m ({} Agenten)",
                (long) p33, (long) p67, distances.size());

        // --- Pass 1: V-Werte per Lookup berechnen, Min/Max je Komponente bestimmen ---
        Map<String, int[]> rawV = new HashMap<>(); // [vEmployment, vAge, vDist]
        int minE = Integer.MAX_VALUE, maxE = Integer.MIN_VALUE;
        int minA = Integer.MAX_VALUE, maxA = Integer.MIN_VALUE;
        int minD = Integer.MAX_VALUE, maxD = Integer.MIN_VALUE;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);
            if (ap == null) continue;

            int vEmployment = employmentToV(ap.employed, ap.homeoffice);
            int vAge        = ageToV(ap.age);
            int vDist       = distanceToV(ap.travelDistanceCarCarpPtM, p33, p67);
            rawV.put(pid, new int[]{vEmployment, vAge, vDist});

            if (vEmployment < minE) minE = vEmployment; if (vEmployment > maxE) maxE = vEmployment;
            if (vAge        < minA) minA = vAge;         if (vAge        > maxA) maxA = vAge;
            if (vDist       < minD) minD = vDist;        if (vDist       > maxD) maxD = vDist;
        }

        // --- Pass 2: Scores mit normalisierten V-Werten berechnen ---
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            int[] v = rawV.get(pid);
            double score;
            if (v == null) {
                score = 0.0;
            } else {
                double normEmployment = normalize(v[0], minE, maxE);
                double normAge        = normalize(v[1], minA, maxA);
                double normDist       = normalize(v[2], minD, maxD);
                score = w1 * normEmployment + w2 * normAge + w3 * normDist;
            }
            scores.put(person.getId(), score);
            totalScore += score;
        }

        // --- Pass 3: Proportional zu Gesamtscore verteilen ---
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("HE_LS: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
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

    // -------------------------------------------------------------------------
    // V-Wert-Mapping
    // -------------------------------------------------------------------------

    /** V_Employment: nicht erwerbstätig=1, Homeoffice=2, Pendler=3. */
    static int employmentToV(boolean employed, String homeoffice) {
        if (!employed) return 1;
        if ("true".equalsIgnoreCase(homeoffice)) return 2;
        return 3;
    }

    /** V_Age: <18=1, >65=2, 18–65=3. */
    static int ageToV(int age) {
        if (age < 18) return 1;
        if (age > 67) return 2;
        return 3;
    }

    /**
     * V_Travel_Distance: Terzil-basiert.
     * ≤ p33 → 1, p33 < x ≤ p67 → 2, > p67 → 3.
     */
    static int distanceToV(double distanceM, double p33, double p67) {
        if (distanceM <= p33) return 1;
        if (distanceM <= p67) return 2;
        return 3;
    }

    private static double normalize(int value, int min, int max) {
        if (max <= min) return 0.5;
        return (double)(value - min) / (max - min);
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v; if (v > max) max = v; sum += v;
        }
        logger.info("HE_LS Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return String.format(
                "Horizontal Equity Life Stage (HE_LS): S = %.1f*V_Employment + %.1f*V_Age + %.1f*V_Travel_Distance",
                w1, w2, w3);
    }
}
