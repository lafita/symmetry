package org.biojava3.structure.align.symm.quaternary;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.vecmath.Point3d;

import org.biojava.bio.structure.Atom;
import org.biojava.bio.structure.Chain;
import org.biojava.bio.structure.Group;
import org.biojava.bio.structure.Structure;
import org.biojava.bio.structure.StructureException;
import org.biojava.bio.structure.StructureTools;
import org.biojava.bio.structure.align.model.AFPChain;
import org.biojava.bio.structure.align.seq.SmithWaterman3Daligner;

public class GlobalSequenceGrouperNew implements SequenceClusterer {
	private double sequenceIdentityThreshold = 0.95;
	private int minSequenceLength = 24;
	private Structure structure = null;
	
	private List<Chain> chains = new ArrayList<Chain>();
	private List<Atom[]> caTraces = new ArrayList<Atom[]>();
	private List<Atom[]> cbTraces = new ArrayList<Atom[]>();
	private List<Point3d[]> caCoords = new ArrayList<Point3d[]>();
	private List<Point3d[]> cbCoords = new ArrayList<Point3d[]>();
	private List<String[]> sequences = new ArrayList<String[]>();
	private boolean sequenceNumberedCorrectly = true;
	private boolean unknownSequence = false;
	private ClusterAlignment clAlign = new ClusterAlignment();
	
//	private List<List<Integer>> clusters40 = new ArrayList<List<Integer>>();
	private List<List<Integer>> clusters100 = new ArrayList<List<Integer>>();
	
//	private Map<String,Integer> sequenceMap = new LinkedHashMap<String,Integer>();
	private Map<Integer,Integer> chainSequenceMap = new LinkedHashMap<Integer,Integer>();
	
	private boolean modified = true;

	GlobalSequenceGrouperNew(Structure structure, int minSequenceLength) {
		this.structure = structure;
		this.minSequenceLength = minSequenceLength;
		modified = true;
	}
	
	public void setStructure(Structure structure) {
		chains.clear();
		caTraces.clear();
		cbTraces.clear();
		caCoords.clear();
		cbCoords.clear();
		sequences.clear();
		clusters100.clear();
//		sequenceMap.clear();
		chainSequenceMap.clear();
		clAlign = new ClusterAlignment();
		sequenceNumberedCorrectly = true;	
		unknownSequence = false;
		modified = true;
		
		this.structure = structure;
	}

	/**
	 * @param minSequenceLength the minSequenceLength to set
	 */
	public void setMinSequenceLength(int minSequenceLength) {
		this.minSequenceLength = minSequenceLength;
		modified = true;
	}
	
	public List<Point3d[]> getCalphaCoordinates() {
        run();
		return caCoords;
	}
	
	public List<Point3d[]> getCbetaCoordinates() {
        run();
		return cbCoords;
	}
	
	public List<Atom[]> getCalphaTraces() {
		run();
		return caTraces;
	}
	
	public List<Atom[]> getCbetaTraces() {
		run();
		return cbTraces;
	}
	
	public List<Chain> getChains() {
        run();
		return chains;
	}
	
	public boolean isSequenceNumberedCorrectly() {
		run();
		return sequenceNumberedCorrectly;
	}
	
	public boolean isUnknownSequence() {
		run();
		return unknownSequence;
	}
	public List<String[]> getSequences() {
		run();
		return sequences;
	}
	
	public boolean isHomomeric() {
		run();
		return clusters100.size() == 1;
	}
	
	public int getMultiplicity() {
		run();
		return clusters100.get(clusters100.size()-1).size();
	}
	
	public List<String> getOrderedChainIDList() {
		run();
		List<String> chainIdList = new ArrayList<String>();

		for (int i = 0; i < clusters100.size(); i++) {
	        List<Integer> cluster = clusters100.get(i);
	        for (int c: cluster) {
	        	chainIdList.add(getChainId(c));
	        }
			
		}
		return chainIdList;
	}
	
	private String getChainId(int index) {
		Chain c = chains.get(index);
		return c.getChainID();
	}
	
	public String getCompositionFormula() {
		run();
		StringBuilder formula = new StringBuilder();
		String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

		for (int i = 0; i < clusters100.size(); i++) {
			String c = "?";
			if (i < alpha.length()) {
				c = alpha.substring(i, i+1);
			}
			formula.append(c);
			int multiplier = clusters100.get(i).size();
			if (multiplier > 1) {
				formula.append(multiplier);
			}
		}
		return formula.toString();
	}
	
	public List<List<Integer>> getSequenceCluster100() {
	    run();
	    return clusters100;
    }

	public List<Integer> getSequenceClusterIds() {
		run();
		Integer[] ids = new Integer[caCoords.size()];
		
		for (int id = 0; id < clusters100.size(); id++) {
			List<Integer> cluster = clusters100.get(id);
			for (int i: cluster) {
				ids[i] = id;
			}
		}
		return Arrays.asList(ids);
	}
	
	public List<Integer> getSequenceIds() {
		run();
		List<Integer> seqIds = new ArrayList<Integer>();
		seqIds.addAll(chainSequenceMap.values());
		Collections.sort(seqIds);
		return seqIds;
	}
	
	private void run() {
		if (! modified) {
			return;
		}
		extractProteinChains();
		calcSequenceClusters100();
		alignClusters();
		System.out.println("Cluster alignments: " + clAlign.getAlignmentCount());
		trimCalphaChains();
		trimCbetaChains();
		if (! isConsistentTraceLength()) {
			System.out.println("WARNING: Inconsistent sequence numbering detected in SequenceGrouper. Splitting sequences into separate clusters.");
			clusters100.clear();
			chains.clear();
			caTraces.clear();
			cbTraces.clear();
			caCoords.clear();
			cbCoords.clear();
			chainSequenceMap.clear();
			extractProteinChains();
			for (int i = 0; i < chains.size(); i++) {
				List<Integer> cluster = new ArrayList<Integer>();
				cluster.add(i);
				clusters100.add(cluster);
			}
		}
		createCalphaTraces();
		createCbetaTraces();
//		clear();
		modified = false;
	}
	
	private void extractProteinChains() {
		int models = 1;
		if (structure.isBiologicalAssembly()) {
			models = structure.nrModels();
		}
		
		String[] cbName = {" CB "};
		
		for (int i = 0; i < models; i++) {
			for (Chain c : structure.getChains(i)) {
				Atom[] ca = StructureTools.getAtomCAArray(c);
				Atom[] cb = StructureTools.getAtomArray(c, cbName);
//				System.out.println("ca: " + ca.length + "cb: " + cb.length);
				if (ca.length >= minSequenceLength) {
				   chains.add(c);
				   caTraces.add(ca);
				   cbTraces.add(cb);
				}
			}
		}
//		System.out.println("caTraces.size: " + caTraces.size());
	}
	
	private void trimCalphaChains() {
		for (List<Integer> cluster: clusters100) {
			// create a list of residue names that the chains of this cluster have in common
			if (cluster.size() > 0) {
				int index = cluster.get(0);
				Set<String> residueNames = getResidueNames(index);
				for (int i = 1; i < cluster.size(); i++) {
					index = cluster.get(i);
					residueNames.retainAll(getResidueNames(index));		
				}
				// create a trimmed list of C-alpha traces only with those 
				// C-alpha atoms that are in common in this sequence cluster

				// Only trim chain if the list of residue names is not empty.
				// This list can be empty if two identical chains are numbered inconsistently,
				// i.e., chain A starts at 1, and chain b starts at 1001.
				if (residueNames.size() > 0) {
					for (int j: cluster) {
						trimCATrace(j, residueNames);
					}
				}
			}
		}
	}
	
	private void trimCbetaChains() {
		for (List<Integer> cluster: clusters100) {
			// create a list of residue names that the chains of this cluster have in common
			if (cluster.size() > 0) {
				int index = cluster.get(0);
				Set<String> residueNames = getResidueNames(index);
				for (int i = 1; i < cluster.size(); i++) {
					index = cluster.get(i);
					residueNames.retainAll(getResidueNames(index));		
				}
				// create a trimmed list of C-alpha traces only with those 
				// C-alpha atoms that are in common in this sequence cluster

				// Only trim chain if the list of residue names is not empty.
				// This list can be empty if two identical chains are numbered inconsistently,
				// i.e., chain A starts at 1, and chain b starts at 1001.
				if (residueNames.size() > 0) {
					for (int j: cluster) {
						trimCBTrace(j, residueNames);
					}
				}
			}
		}
	}

	
	private void trimCATrace(int index, Set<String> residueNames) {
		List<Atom> trace = new ArrayList<Atom>(residueNames.size());	
		
		for (Atom a:  caTraces.get(index)) {
			Group g = a.getGroup();
			String residueName = g.getResidueNumber() + g.getPDBName();
			if (residueNames.contains(residueName)) {
				trace.add(a);
			}
		}
		caTraces.set(index, trace.toArray(new Atom[0]));
	}
	
	private void trimCBTrace(int index, Set<String> residueNames) {
		List<Atom> trace = new ArrayList<Atom>(residueNames.size());
		
		for (Atom a:  cbTraces.get(index)) {
			Group g = a.getGroup();
			String residueName = g.getResidueNumber() + g.getPDBName();
			if (residueNames.contains(residueName)) {
				trace.add(a);
			}
		}
		cbTraces.set(index, trace.toArray(new Atom[0]));
	}

	private Set<String> getResidueNames(int index) {
		Set<String> residueNames = new HashSet<String>();

		for (Atom a:  caTraces.get(index)) {
			Group g = a.getGroup();
			if (g.getPDBName().equals("UNK")) {
				unknownSequence = true;
			}
		    residueNames.add(g.getResidueNumber() + g.getPDBName());
		}
		return residueNames;
	}
	
	private boolean isConsistentTraceLength() {
		for (List<Integer> cluster: clusters100) {
			// create a list of residue names that the chains of this cluster have in common
			int index = cluster.get(0);
			int caLen = caTraces.get(index).length;
			int cbLen = cbTraces.get(index).length;
			for (int i = 1; i < cluster.size(); i++) {
				int j = cluster.get(i);
				if (caLen != caTraces.get(j).length) {
					System.out.println("CA Length inconsistency: " + index + " - " + j + ": " + caLen + "/" + caTraces.get(j).length);
					sequenceNumberedCorrectly = false;
					return false;
				}
				if (cbLen != cbTraces.get(j).length) {
					System.out.println("CB Length inconsistency: " + index + " - " + j + ": " + cbLen + "/" + cbTraces.get(j).length);
					sequenceNumberedCorrectly = false;
					return false;
				}
			}
		}
		return true;
	}
	
	private void createCalphaTraces() {
		for (Atom[] atoms: caTraces) {
			Point3d[] trace = new Point3d[atoms.length];
			for (int j = 0; j < atoms.length; j++) {
				trace[j] = new Point3d(atoms[j].getCoords());
			}
			caCoords.add(trace);
		}
	}
	
	private void createCbetaTraces() {
		for (Atom[] atoms: cbTraces) {
			Point3d[] trace = new Point3d[atoms.length];
			for (int j = 0; j < atoms.length; j++) {
				trace[j] = new Point3d(atoms[j].getCoords());
			}
			cbCoords.add(trace);
		}
	}
	
	private void calcSequenceClusters100() {
		boolean[] processed = new boolean[chains.size()];
		Arrays.fill(processed, false);
		Map<String,Integer> sequenceMap = new LinkedHashMap<String,Integer>();

		for (int i = 0; i < chains.size(); i++) {
			String s1 = chains.get(i).getSeqResSequence();	
			Integer id1 = sequenceMap.get(s1);
			if (id1 == null) {
				id1 =  sequenceMap.size();
				sequenceMap.put(s1, id1);
			}
			chainSequenceMap.put(i, id1);
		
			if (processed[i]) {
				continue;
			}
			for (int j = i + 1; j < chains.size(); j++) {
				if (processed[j]) {
					continue;
				}
				String s2 = chains.get(j).getSeqResSequence();
				Integer id2 = sequenceMap.get(s2);
				if (id2 == null) {
					id2 =  sequenceMap.size();
					sequenceMap.put(s2, id2);
				}
				chainSequenceMap.put(j, id2);
			
				if (s1.equals(s2)) {
//					System.out.println("Same: " + i + " - " + j);
//					System.out.println(s1);
//					System.out.println(s2);
					processed[j] = true;
					processed[i] = true;
					addToCluster(i, j, clusters100);
				} else {
//					System.out.println("Mismatch: " + i + " - " + j);
//					System.out.println(s1);
//					System.out.println(s2);
				}
			}
			if (! processed[i]) {
				// add chain i to its own cluster
				addToCluster(i, i, clusters100);
			}
		}
		sortClusterBySize(clusters100);
	}
	
	private void addToCluster(int i, int j, List<List<Integer>> clusters) {
//		System.out.println("Adding to cluster: " + i + " " + j);
		if (i != j) {
			for (List<Integer> cluster : clusters) {
				if (cluster.contains(i)) {
					cluster.add(j);
					return;
				}
			}
		}
		List<Integer> cluster = new ArrayList<Integer>();
		cluster.add(i);
		
		if (i != j) {
			cluster.add(j);
		}
		clusters.add(cluster);	
	}
	
	public void sortClusterBySize(List<List<Integer>> clusters) {
		Collections.sort(clusters, new Comparator<List<Integer>>() {
			public int compare(List<Integer> l1, List<Integer> l2) {
				return Math.round(Math.signum(l2.size() - l1.size()));
			}
		});
	}
	
	private void alignClusters() {
		System.out.println("alignClusters: " + clusters100.size());
        for (int i = 0; i < clusters100.size() - 1; i++) {
 //       for (List<Integer> c1: clusters1) {
        	// get the first C alpha array from a cluster, as the representative of this cluster
        	List<Integer> c1 = clusters100.get(i);
        	int representative1 = c1.get(0); 

        	Atom[] ca1Seq = caTraces.get(representative1);
            for (int j = i + 1; j < clusters100.size(); j++) {
 //       	for (List<Integer> c2: clusters2) {
            	List<Integer> c2 = clusters100.get(j);
        		int representative2 = c2.get(0); 
            	System.out.print("Comparing: " + representative1);
            	System.out.println(" - " + representative2);
            	Atom[] ca2Seq = caTraces.get(representative2);
            	System.out.println("Ca length: " + ca1Seq.length + " - " + ca2Seq.length);
            	long t1 = System.nanoTime();
        		AFPChain afp = alignPair(ca1Seq, ca2Seq);
        		if (afp == null) {
        			System.out.println("AFPChain is null");
        			continue;
        		}
        		double identity = afp.getIdentity();
        		long t2 = System.nanoTime();
        		System.out.println("Alignment: " + (t2-t1)/1000000 + " ms");

        		// store alignment information for two matching clusters
        		// TODO should only keep best match, id may be as low at 0.9
        		if (identity >= sequenceIdentityThreshold) {
        			System.out.println("Seq. identity: " + afp.getIdentity());
        			System.out.println("Rmsd: " + afp.getChainRmsd());
        			int[][][] alig = afp.getOptAln();
          			if (alig == null) {
        				continue;
        			}
        			System.out.println("Align1: " + Arrays.toString(alig[0][0]));
        			System.out.println("Align2: " + Arrays.toString(alig[0][1]));
        			List<Integer> alignment1 = new ArrayList<Integer>();
  
        			for (Integer a1: alig[0][0]) {
        				alignment1.add(a1);
        			}
        			List<Integer> alignment2 = new ArrayList<Integer>();
        			for (Integer a2: alig[0][1]) {
        				alignment2.add(a2);
        			}
        			clAlign.add(c1, c2, alignment1, alignment2, identity);
        			if (alignment1.size() != alignment2.size()) {
        				System.out.println("alignUniqueSequences: ERROR: alignment length mismatch");
        				continue;
        			}
        			break;
        		}    		
        	}
        }
	}
	
	private AFPChain alignPair(Atom[] ca1Seq, Atom[] ca2Seq) {
		SmithWaterman3Daligner aligner = new SmithWaterman3Daligner();
		AFPChain afp = null;
		try {
			afp = aligner.align(ca1Seq, ca2Seq);
		} catch (StructureException e) {
			e.printStackTrace();
			return afp;
		} 
		return afp;
	}
	
	private void createCalphas() {
		List<Integer> currentCluster = clAlign.getCluster1(0);
	    Set<Integer> ids = new HashSet<Integer>();
	    List<Integer> clusterIds = new ArrayList<Integer>();
		for (int i = 0; i < clAlign.getAlignmentCount(); i++) {
			List<Integer> cluster1 = clAlign.getCluster1(i);
			if (!cluster1.equals(currentCluster)) {
				createCalphaLists(ids, clusterIds);
			    ids.clear();
			    currentCluster = cluster1;
			    clusterIds.clear();
			}
			ids.addAll(clAlign.getAlignment1(i));
		}
	}
	
	private void createCalphaLists(Set<Integer> ids, List<Integer> clusterIds) {
		List<Atom[]> alignedCaTraces = new ArrayList<Atom[]>();
		for (int clusterId: clusterIds) {
			List<Integer> alignment1 = clAlign.getAlignment1(clusterId);
			List<Integer> alignment2 = clAlign.getAlignment2(clusterId);
			for (int i: clAlign.getCluster1(clusterId)) {
				List<Atom> atoms = new ArrayList<Atom>();
				Atom[] ca = caTraces.get(i);
				for (int a: alignment2) {
					if (ids.contains(a)) {
						atoms.add(ca[a]);
					}
				}
				Atom[] alAtoms = new Atom[atoms.size()];
				alAtoms = atoms.toArray(alAtoms);
				alignedCaTraces.add(alAtoms);
			}
		}
	}

	private Atom[] createCalphaList(Atom[] caSeq, List<Integer> alignment) {
		Atom[] subset = new Atom[alignment.size()];
//		System.out.println("createCalphaList: len/alignment: " + caSeq.length + ": "+ alignment);
		if (alignment.size() > caSeq.length) {
			System.out.println("createCalphaList: ERROR: size mismatch");
			return null;
		}
		for (int i = 0; i < alignment.size(); i++) {
			if (alignment.get(i) >= caSeq.length) {
				return null;
			}
			subset[i] = caSeq[alignment.get(i)];
		}
		return subset;
	}
}