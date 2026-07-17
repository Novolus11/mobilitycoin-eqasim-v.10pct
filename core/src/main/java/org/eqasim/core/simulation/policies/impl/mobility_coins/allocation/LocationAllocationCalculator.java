package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Location (LOCATION) allocation.
 *
 * Score: S = W1 * norm(Zone_Home) + W2 * (norm(Zone_Work) + norm(Zone_Education))
 *
 * Zonenwerte (1–5, Burgess-Zonen) werden min-max-normalisiert:
 * höhere Zonennummer = weiter vom Zentrum = höherer Score = mehr Coins.
 *
 * NaN-Zonen → Wert = 0. Min/Max wird dynamisch über alle Agenten berechnet (inkl. 0-Werte),
 *
 * Coins je Agent: coins_i = totalCoins × (S_i / Σ S_i)
 */
public class LocationAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(LocationAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;
    private final double w1; // Gewicht Zone_Home
    private final double w2; // Gewicht Zone_Work + Zone_Education

    public LocationAllocationCalculator(
            Map<String, AgentParametersPrecomputer.AgentParams> agentParams,
            double w1, double w2) {
        this.agentParams = agentParams;
        this.w1 = w1;
        this.w2 = w2;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // -------------------------------------------------------------------------
        // Pass 1: Rohwerte einlesen + Min/Max je Zonentyp bestimmen
        //
        // Quelle: ap.homeZone / ap.workZone / ap.educationZone aus der agentParams-CSV
        // Format: "1"–"5" oder "NaN" (kein Aktivitätsort) → wird als 0 behandelt
        //
        // Alle Werte inkl. der 0er (NaN-Agenten) fließen in Min/Max ein,
        //
        // Ranges nach Pass 1 (abhängig von den Daten):
        //   home_zone:      min=0, max=5  (0 wenn Heimzone NaN oder kein agentParams-Eintrag)
        //   work_zone:      min=0, max=5  (0 für Agenten ohne Arbeitsort)
        //   education_zone: min=0, max=5  (0 für Agenten ohne Bildungsort)
        // -------------------------------------------------------------------------
        Map<String, int[]> rawZones = new HashMap<>(); // personId → [home, work, edu]
        double minHome = Double.MAX_VALUE, maxHome = -Double.MAX_VALUE;
        double minWork = Double.MAX_VALUE, maxWork = -Double.MAX_VALUE;
        double minEdu  = Double.MAX_VALUE, maxEdu  = -Double.MAX_VALUE;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);

            // NaN oder kein agentParams-Eintrag → Zonenwert = 0
            int home = (ap != null) ? parseZone(ap.homeZone) : 0;
            int work = (ap != null) ? parseZone(ap.workZone) : 0;
            int edu  = (ap != null) ? parseZone(ap.educationZone) : 0;
            rawZones.put(pid, new int[]{home, work, edu});

            if (home < minHome) minHome = home; if (home > maxHome) maxHome = home;
            if (work < minWork) minWork = work; if (work > maxWork) maxWork = work;
            if (edu  < minEdu)  minEdu  = edu;  if (edu  > maxEdu)  maxEdu  = edu;
        }

        logger.info("LOCATION Min/Max – home:[{},{}] work:[{},{}] edu:[{},{}]",
                (int)minHome, (int)maxHome, (int)minWork, (int)maxWork, (int)minEdu, (int)maxEdu);

        // -------------------------------------------------------------------------
        // Pass 2: Score je Agent berechnen
        //
        // Normalisierung: norm(x) = (x - min) / (max - min)  → Wertebereich [0, 1]
        //   norm(0, min=0, max=5) = 0.0   (kein Aktivitätsort)
        //   norm(1, min=1, max=5) = 0.0   (innerste Zone)
        //   norm(5, min=1, max=5) = 1.0   (äußerste Zone)
        //
        // Score-Formel (entspricht Excel):
        //   S = w1 * norm(Zone_Home) + w2 * (norm(Zone_Work) + norm(Zone_Education))
        //   mit w1=0.6, w2=0.4 (konfigurierbar via --moco:locationWeightHome / locationWeightWorkEdu)
        //
        // -------------------------------------------------------------------------
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            int[] z = rawZones.get(pid);
            double score = 0.0;
            if (z != null) {
                double normHome = normalize(z[0], minHome, maxHome); // z[0] = home_zone (1–5 oder 0)
                double normWork = normalize(z[1], minWork, maxWork); // z[1] = work_zone (1–5 oder 0)
                double normEdu  = normalize(z[2], minEdu,  maxEdu);  // z[2] = education_zone (1–5 oder 0)
                score = w1 * normHome + w2 * (normWork + normEdu);
            }
            scores.put(person.getId(), score);
            totalScore += score;
        }

        // -------------------------------------------------------------------------
        // Pass 3: Coins proportional zum Score verteilen
        //
        // Formel: coins_i = totalCoins × (S_i / Σ S_i)
        // Agenten mit score=0 bekommen 0 Coins.
        // Fallback auf Gleichverteilung wenn totalScore=0.
        // -------------------------------------------------------------------------
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("LOCATION: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
            double coinsPerPerson = totalCoins / population.getPersons().size();
            for (Person person : population.getPersons().values()) {
                allocations.put(person.getId(), coinsPerPerson);
            }
        } else {
            for (Person person : population.getPersons().values()) {
                double s = scores.getOrDefault(person.getId(), 0.0);
                // coins = totalCoins × (eigener Score / Summe aller Scores)
                allocations.put(person.getId(), totalCoins * (s / totalScore));
            }
        }

        logStats(allocations);
        return allocations;
    }

    /** Parst Zonennummer (1–5); "NaN", null oder leer → 0. */
    private static int parseZone(String zone) {
        if (zone == null || zone.isBlank() || "NaN".equalsIgnoreCase(zone)) return 0;
        try { return Integer.parseInt(zone.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static double normalize(double value, double min, double max) {
        if (max <= min) return 0.0;
        return (value - min) / (max - min);
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        logger.info("LOCATION Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return String.format("Location (LOCATION): S = %.1f*norm(Zone_Home) + %.1f*(norm(Zone_Work) + norm(Zone_Education))", w1, w2);
    }
}
