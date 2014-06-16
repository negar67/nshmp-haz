package org.opensha.gmm;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.log;
import static org.opensha.gmm.FaultStyle.NORMAL;
import static org.opensha.gmm.FaultStyle.REVERSE;
import static org.opensha.gmm.FaultStyle.STRIKE_SLIP;
import static org.opensha.gmm.FaultStyle.UNKNOWN;
import static org.opensha.gmm.SiteClass.HARD_ROCK;
import static org.opensha.gmm.SiteClass.SOFT_ROCK;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensha.calc.ScalarGroundMotion;
import org.opensha.util.Parsing;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * Ground motion model (Gmm) utilities.
 * @author Peter Powers
 */
public final class GmmUtils {

	/** 
	 * Base-10 to base-e conversion factor commonly used with ground motion
	 * lookup tables that supply log10 instead of natural log values.
	 */
	public static final double BASE_10_TO_E = log(10.0);
	
	// NOTE the above conversion factor is 2.302585092994046 and is commonly
	// referenced as SFAC in ported codes
	
	/**
	 * Returns the NSHMP interpretation of fault type based on rake; divisions
	 * are on 45&deg; diagonals.
	 * 
	 * <p><b>Note:</b> This is inconsistent with next generation attenuation
	 * relationship (NGAW1 and NGAW2) recommendations.</p>
	 * 
	 * @param rake to convert (in degrees)
	 * @return the corresponding {@code FaultStyle}
	 */
	public static FaultStyle rakeToFaultStyle_NSHMP(double rake) {
		if (Double.isNaN(rake)) return UNKNOWN;
		return (rake >= 45 && rake <= 135) ? REVERSE
			: (rake >= -135 && rake <= -45) ? NORMAL : STRIKE_SLIP;
	}	
		
	// internally values are converted and/or scaled up to
	// integers to eliminate decimal precision errors:
	// Mag = (int) M*100
	// Dist = (int) Math.floor(D)
	// Period = (int) P*1000

	// <Mag, <Dist, val>>
	private static Map<Integer, Map<Integer, Double>> rjb_map;
	// <Period <Mag, <Dist, val>>>
	private static Map<Integer, Map<Integer, Map<Integer, Double>>> cbhw_map;
	private static Map<Integer, Map<Integer, Map<Integer, Double>>> cyhw_map;

	private static String datPath = "etc/";
	private static String rjbDatPath = datPath + "rjbmean.dat";
	private static String cbhwDatPath = datPath + "avghw_cb.dat";
	private static String cyhwDatPath = datPath + "avghw_cy.dat";

	static {
		rjb_map = new HashMap<Integer, Map<Integer, Double>>();
		cbhw_map = new HashMap<Integer, Map<Integer, Map<Integer, Double>>>();
		cyhw_map = new HashMap<Integer, Map<Integer, Map<Integer, Double>>>();
		readRjbDat();
		readHwDat(cbhw_map, cbhwDatPath, 6.05);
		readHwDat(cyhw_map, cyhwDatPath, 5.05);
	}

	private static void readRjbDat() {

		String magID = "#Mag";
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(
				GmmUtils.class.getResourceAsStream(rjbDatPath)));
			String line;
			HashMap<Integer, Double> magMap = null;
			while ((line = br.readLine()) != null) {
				if (line.startsWith(magID)) {
					double mag = Double.parseDouble(line.substring(
						magID.length() + 1).trim());
					int magKey = new Double(mag * 100).intValue();
					magMap = new HashMap<Integer, Double>();
					rjb_map.put(magKey, magMap);
					continue;
				}
				if (line.startsWith("#")) continue;
				
				List<String> dVal = Lists.newArrayList(Parsing.splitOnSpaces(line));
				if (dVal.size() == 0) continue;
				int distKey = new Double(dVal.get(0)).intValue();
				magMap.put(distKey, Double.parseDouble(dVal.get(1)));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void readHwDat(
			Map<Integer, Map<Integer, Map<Integer, Double>>> map, String path,
			double startMag) {
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(
				GmmUtils.class.getResourceAsStream(path)));
			String line;
			Map<Integer, Map<Integer, Double>> periodMap = null;
			while ((line = br.readLine()) != null) {
				if (line.startsWith("C")) {
					// period map
					Iterable<String> parts = Parsing.splitOnSpaces(line);
					double per = Double.parseDouble(Iterables.get(parts, 4));
					int perKey = (int) (per * 1000);
					periodMap = new HashMap<Integer, Map<Integer, Double>>();
					map.put(perKey, periodMap);
					continue;
				}
				List<String> values = Lists.newArrayList(Parsing.splitOnSpaces(line));
				if (values.size() == 0) continue;
				int distKey = Integer.parseInt(values.get(0));
				int magIdx = Integer.parseInt(values.get(1));
				int magKey = (int) (startMag * 100) + (magIdx - 1) * 10;
				double hwVal = Double.parseDouble(values.get(2));
				Map<Integer, Double> magMap = periodMap.get(magKey);
				if (magMap == null) {
					magMap = new HashMap<Integer, Double>();
					periodMap.put(magKey, magMap);
				}
				magMap.put(distKey, hwVal);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Returns a corrected distance value corresponding to the supplied JB
	 * distance and magnitude. Magnitude is expected to be a 0.05 centered value
	 * between 6 and 7.6 (e.g [6.05, 6.15, ... 7.55]). Distance values should be
	 * &lt;1000km. If <code>D</code> &ge; 1000km, method returns D.
	 * 
	 * @param M magnitude
	 * @param D distance
	 * @return the corrected distance or <code>D</code> if <code>D</code> ??? 1000
	 * @throws IllegalArgumentException if <code>M</code> is not one of [6.05,
	 *         6.15, ... 8.55]
	 */
	public static double getMeanRJB(double M, double D) {
		int magKey = (int) Math.round(M * 100);
		checkArgument(rjb_map.containsKey(magKey), "Invalid mag value: " + M);
		int distKey = (int) Math.floor(D);
		return (D <= 1000) ? rjb_map.get(magKey).get(distKey) : D;
	}

	/**
	 * Returns the average hanging-wall factor appropriate for
	 * {@link CampbellBozorgnia_2008} for a dipping point source at the supplied
	 * distance and magnitude and period of interest. Magnitude is expected to
	 * be a 0.05 centered value between 6 and 7.5 (e.g [6.05, 6.15, ... 7.45]).
	 * Distance values should be &le;200km. If distance value is &gt200km,
	 * method returns 0. Valid periods are those prescribed by
	 * {@link CampbellBozorgnia_2008}.
	 * 
	 * @param M magnitude
	 * @param D distance
	 * @param P period
	 * @return the hanging wall factor
	 * @throws IllegalArgumentException if <code>M</code> is not one of [6.05,
	 *         6.15, ... 7.45]
	 * @throws IllegalArgumentException if <code>P</code> is not one of [-2.0
	 *         (pgd), -1.0 (pgv), 0.0 (pga), 0.01, 0.02, 0.03, 0.04, 0.05,
	 *         0.075, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.75, 1.0, 1.5, 2.0,
	 *         3.0, 4.0, 5.0, 7.5, 10.0]
	 */
	public static double getAvgHW_CB(double M, double D, double P) {
		return getAvgHW(cbhw_map, M, D, P);
	}

	/**
	 * Returns the average hanging-wall factor appropriate for
	 * {@link ChiouYoungs_2008} for a dipping point source at the supplied
	 * distance and magnitude and period of interest. Magnitude is expected to
	 * be a 0.05 centered value between 5 and 7.5 (e.g [5.05, 6.15, ... 7.45]).
	 * If there is no match for the supplied magnitude, method returns 0.
	 * Distance values should be &le;200km. If distance value is &gt200km,
	 * method returns 0. Valid periods are those prescribed by
	 * {@link ChiouYoungs_2008} (<em>Note:</em>PGV is currently missing).
	 * 
	 * @param M magnitude
	 * @param D distance
	 * @param P period
	 * @return the hanging wall factor
	 * @throws IllegalArgumentException if <code>P</code> is not one of [0.0
	 *         (pga), 0.01, 0.02, 0.03, 0.04, 0.05, 0.075, 0.1, 0.15, 0.2, 0.25,
	 *         0.3, 0.4, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 7.5, 10.0]
	 */
	public static double getAvgHW_CY(double M, double D, double P) {
		return getAvgHW(cyhw_map, M, D, P);
	}

	private static double getAvgHW(
			Map<Integer, Map<Integer, Map<Integer, Double>>> map, double M,
			double D, double P) {
		int perKey = (int) (P * 1000);
		checkArgument(map.containsKey(perKey), "Invalid period: " + P);
		Map<Integer, Map<Integer, Double>> magMap = map.get(perKey);
		int magKey = new Double(M * 100).intValue();
		if (!magMap.containsKey(magKey)) return 0;
		int distKey = new Double(Math.floor(D)).intValue();
		return (distKey > 200) ? 0 : magMap.get(magKey).get(distKey);
	}
	
	
	/**
	 * Period dependent ground motion clipping. For PGA (<code>period</code> =
	 * 0.0s), method returns <code>Math.min(ln(1.5g), gnd)</code>; for
	 * <code>0.02s &lt; period &lt; 0.5s</code>, method returns
	 * <code>Math.min(ln(3.0g), gnd)</code>. This is used to clip the upper tail
	 * of the exceedance curve.
	 * @param imt of interest
	 * @param gnd log ground motion ln(g)
	 * @return the clipped ground motion if required by the supplied
	 *         <code>period</code>, <code>gnd</code> otherwise
	 */
	public static double ceusMeanClip(IMT imt, double gnd) {
		// ln(1.5) = 0.405; ln(3.0) = 1.099
		if (imt == IMT.PGA) return Math.min(0.405, gnd);
		if (imt.period() > 0.02 && imt.period() < 0.5) return Math.min(gnd, 1.099);
		return gnd;
	}
	
	/**
	 * Returns a site calss identifier for use with CEUS GMMs.
	 * @param vs30 
	 * @return the site class corresponding to the supplied vs30
	 */
	public static SiteClass ceusSiteClass(double vs30) {
		if (vs30 == 760.0) return SOFT_ROCK;
		if (vs30 == 2000.0) return HARD_ROCK;
		throw new IllegalArgumentException("Unsupported CEUS vs30: " + vs30);
	}


}