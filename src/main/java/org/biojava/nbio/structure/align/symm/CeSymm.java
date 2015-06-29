package org.biojava.nbio.structure.align.symm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.StructureException;
import org.biojava.nbio.structure.StructureTools;
import org.biojava.nbio.structure.align.AbstractStructureAlignment;
import org.biojava.nbio.structure.align.MultipleStructureAligner;
import org.biojava.nbio.structure.align.StructureAlignment;
import org.biojava.nbio.structure.align.ce.CECalculator;
import org.biojava.nbio.structure.align.ce.CeCPMain;
import org.biojava.nbio.structure.align.ce.ConfigStrucAligParams;
import org.biojava.nbio.structure.align.ce.MatrixListener;
import org.biojava.nbio.structure.align.model.AFPChain;
import org.biojava.nbio.structure.align.multiple.MultipleAlignment;
import org.biojava.nbio.structure.align.multiple.MultipleAlignmentEnsembleImpl;
import org.biojava.nbio.structure.align.symm.CESymmParameters.RefineMethod;
import org.biojava.nbio.structure.align.symm.CESymmParameters.SymmetryType;
import org.biojava.nbio.structure.align.symm.order.OrderDetectionFailedException;
import org.biojava.nbio.structure.align.symm.order.OrderDetector;
import org.biojava.nbio.structure.align.symm.order.SequenceFunctionOrderDetector;
import org.biojava.nbio.structure.align.symm.refine.MultipleRefiner;
import org.biojava.nbio.structure.align.symm.refine.OpenRefiner;
import org.biojava.nbio.structure.align.symm.refine.Refiner;
import org.biojava.nbio.structure.align.symm.refine.RefinerFailedException;
import org.biojava.nbio.structure.align.symm.refine.SingleRefiner;
import org.biojava.nbio.structure.align.symm.refine.SymmOptimizer;
import org.biojava.nbio.structure.align.util.AFPChainScorer;
import org.biojava.nbio.structure.align.util.AtomCache;
import org.biojava.nbio.structure.align.util.RotationAxis;
import org.biojava.nbio.structure.jama.Matrix;
import org.biojava.nbio.structure.utils.SymmetryTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identify the symmetries in a structure by running an alignment of the structure
 * against itself disabling the diagonal of the identity alignment. Iterating recursively over all results
 * and disabling the diagonal of each previous result can also be done with the current implementation.
 * 
 * @author andreas
 * 
 */
public class CeSymm extends AbstractStructureAlignment implements MatrixListener, StructureAlignment, MultipleStructureAligner {

	private static final boolean debug = false;
	public static final String algorithmName = "jCE-symmetry";
	public static final String version = "1.0";
	private static final Logger logger = LoggerFactory.getLogger(CeSymm.class);
	public static final double symmetryThreshold = 0.4;

	private AFPChain afpChain;
	private List<AFPChain> afpAlignments;
	public SymmetryType type;

	private Atom[] ca1;
	private Atom[] ca2;
	private int rows;
	private int cols;
	
	private CECalculator calculator;
	private CESymmParameters params;
	
	public CeSymm() {
		super();
		params = new CESymmParameters();
	}

	public static void main(String[] args) {
		// Responsible for creating a CeMain instance
		CeSymmUserArgumentProcessor processor = new CeSymmUserArgumentProcessor(); 
		processor.process(args);
	}

	public static String toDBSearchResult(AFPChain afpChain) {
		StringBuffer str = new StringBuffer();

		str.append(afpChain.getName1());
		str.append("\t");
		str.append(afpChain.getName2());
		str.append("\t");
		str.append(String.format("%.2f", afpChain.getAlignScore()));
		str.append("\t");
		str.append(String.format("%.2f", afpChain.getProbability()));
		str.append("\t");
		str.append(String.format("%.2f", afpChain.getTotalRmsdOpt()));
		str.append("\t");
		str.append(afpChain.getCa1Length());
		str.append("\t");
		str.append(afpChain.getCa2Length());
		str.append("\t");
		str.append(afpChain.getCoverage1());
		str.append("\t");
		str.append(afpChain.getCoverage2());
		str.append("\t");
		str.append(String.format("%.2f", afpChain.getTMScore()));
		str.append("\t");
		str.append(afpChain.getOptLength());
		return str.toString();
	}

	public AFPChain indentifyAllSymmetries(String name1, String name2,
			AtomCache cache, int fragmentLength) throws StructureException,
			IOException {

		params = new CESymmParameters();

		params.setWinSize(fragmentLength);

		Atom[] ca1 = cache.getAtoms(name1);
		Atom[] ca2 = cache.getAtoms(name2);

		return align(ca1, ca2, params);
	}

	private static Matrix align(AFPChain afpChain, Atom[] ca1, Atom[] ca2, CESymmParameters params, 
			Matrix origM, CECalculator calculator, int counter) throws StructureException {

		int fragmentLength = params.getWinSize();
		Atom[] ca2clone = SymmetryTools.cloneAtoms(ca2);

		int rows = ca1.length;
		int cols = ca2.length;

		// Matrix that tracks similarity of a fragment of length fragmentLength
		// starting a position i,j.

		int blankWindowSize = fragmentLength;
		if (origM == null) {
			// Build alignment ca1 to ca2-ca2

			afpChain = calculator.extractFragments(afpChain, ca1, ca2clone);

			origM = SymmetryTools.blankOutPreviousAlignment(afpChain, ca2,
					rows, cols, calculator, null, blankWindowSize);

		} else {
			// we are doing an iteration on a previous alignment
			// mask the previous alignment
			origM = SymmetryTools.blankOutPreviousAlignment(afpChain, ca2,
					rows, cols, calculator, origM, blankWindowSize);
		}

		Matrix clone = (Matrix) origM.clone();

		// that's the matrix to run the alignment on..
		calculator.setMatMatrix(clone.getArray());

		calculator.traceFragmentMatrix(afpChain, ca1, ca2clone);
		
		final Matrix origMfinal = (Matrix) origM.clone();
		//Add a matrix listener to keep the blacked zones in max.
		calculator.addMatrixListener(new MatrixListener() {

			@Override
			public double[][] matrixInOptimizer(double[][] max) {
				
				//Check every entry of origM for blacked out regions
				for (int i=0; i<max.length; i++){
					for (int j=0; j<max[i].length; j++){
						if (origMfinal.getArray()[i][j]>1e9){
							max[i][j] = -origMfinal.getArray()[i][j];
						}
					}
				}
				return max;
			}

			@Override
			public boolean[][] initializeBreakFlag(boolean[][] brkFlag) {
				
				return brkFlag;
			}
		});
		
		calculator.nextStep(afpChain, ca1, ca2clone);

		afpChain.setAlgorithmName(algorithmName);
		afpChain.setVersion(version);

		afpChain.setDistanceMatrix(origM);
		
		return origMfinal;

	}

	@Override
	public double[][] matrixInOptimizer(double[][] max) {

		return CECalculator.updateMatrixWithSequenceConservation(max, ca1, ca2,
				params);
	}

	@Override
	public boolean[][] initializeBreakFlag(boolean[][] breakFlag) {
		int fragmentLength = params.getWinSize();
		try {
			if (afpChain != null) {
				breakFlag = SymmetryTools.blankOutBreakFlag(afpChain, ca2,
						rows, cols, calculator, breakFlag, fragmentLength);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return breakFlag;

	}

	public CECalculator getCalculator() {
		return calculator;
	}

	public void setCalculator(CECalculator calculator) {
		this.calculator = calculator;
	}

	@Override
	public AFPChain align(Atom[] ca1, Atom[] ca2) throws StructureException {

		if (params == null)	params = new CESymmParameters();
		return align(ca1, ca2, params);
	}

	@Override
	public AFPChain align(Atom[] ca10, Atom[] ca2O, Object param) throws StructureException {
		
	//STEP 0: prepare all the information for the symmetry alignment
		if (!(param instanceof CESymmParameters))
			throw new IllegalArgumentException("CE algorithm needs an object of call CESymmParameters as argument.");

		this.params = (CESymmParameters) param;

		ca1 = ca10;
		ca2 = StructureTools.duplicateCA2(ca2O);
		rows = ca1.length;
		cols = ca2.length;
		
		if( rows == 0 || cols == 0) {
			logger.warn("Aligning empty structure");
			throw new StructureException("Aligning empty structure");
		}

		Matrix origM = null;
		AFPChain myAFP = new AFPChain();
		afpAlignments = new ArrayList<AFPChain>();
		
		calculator = new CECalculator(params);
		calculator.addMatrixListener(this);
		
		//Set multiple to true if multiple alignments are needed for refinement
		boolean multiple = (params.getRefineMethod() == RefineMethod.MULTIPLE);
		Matrix lastMatrix = null;

	//STEP 1: perform the raw symmetry alignment
		int i = 0;
		do {
			
			if (origM != null) {
				myAFP.setDistanceMatrix((Matrix) origM.clone());
			}
			origM = align(myAFP, ca1, ca2, params, origM, calculator, i);

			double tmScore2 = AFPChainScorer.getTMScore(myAFP, ca1, ca2);
			myAFP.setTMScore(tmScore2);
			
			//Clone the AFPChain
			AFPChain newAFP = (AFPChain) myAFP.clone();
			
			//Post process the alignment
			newAFP = CeCPMain.postProcessAlignment(newAFP, ca1, ca2, calculator);
			
			//Calculate and set the TM score for the newAFP alignment
			double tmScore3 = AFPChainScorer.getTMScore(newAFP, ca1, ca2);
			newAFP.setTMScore(tmScore3);
			if (debug) logger.info("Alignment "+(i+1)+" score: "+newAFP.getTMScore());
			//Determine if the alignment is significant to do more alignment iterations
			if (tmScore3 < symmetryThreshold){
				if(debug) logger.info("Not symmetric alignment with TM score: "+newAFP.getTMScore());
				//If it is the first alignment save it anyway and try to optimize it
				if (i==0) afpAlignments.add(newAFP);

				//store final matrix & 
				lastMatrix = newAFP.getDistanceMatrix().copy();
				break;
			}
			//If it is a symmetric alignment add it to the allAlignments list
			afpAlignments.add(newAFP);
			
			i++;
			
		} while (i < params.getMaxSymmOrder() && multiple);
		if(lastMatrix == null && afpAlignments.size()>1 ) {
			// we reached the maximum order, so need to blank out the final alignment
			AFPChain last = afpAlignments.get( afpAlignments.size()-1 );
			lastMatrix = SymmetryTools.blankOutPreviousAlignment(last,ca2, last.getCa1Length(), last.getCa2Length(), calculator, origM, params.getWinSize());
			lastMatrix = lastMatrix.getMatrix(0, last.getCa1Length()-1, 0, last.getCa2Length()-1);
		}
		
		//Save the results to the CeSymm member variables
		afpChain = afpAlignments.get(0);
		
		//Determine the symmetry Type or get the one in params
		type = params.getSymmetryType();
		if (type == SymmetryType.AUTO){
			if (afpChain.getBlockNum() == 1) type = SymmetryType.OPEN;
			else type = SymmetryType.CLOSED;
		}
		
	//STEP 2: calculate the order of symmetry for CLOSED symmetry
		int order = 1;
		OrderDetector orderDetector = null;
		switch (params.getOrderDetectorMethod()) {
		case SEQUENCE_FUNCTION: 
			orderDetector = new SequenceFunctionOrderDetector(params.getMaxSymmOrder(), 0.4f);
			break;
		}
		try {
			order = orderDetector.calculateOrder(afpChain, ca1);
		} catch (OrderDetectionFailedException e) {
			e.printStackTrace();
		}
		
	//STEP 3: symmetry refinement, apply consistency in the subunit residues
		Refiner refiner = null;
		switch (params.getRefineMethod()){
		case MULTIPLE:
			if (type == SymmetryType.CLOSED) refiner = new MultipleRefiner(orderDetector);
			else refiner = new OpenRefiner();
			break;
		case SINGLE:
			if (type == SymmetryType.CLOSED) refiner = new SingleRefiner();
			else refiner = new OpenRefiner();
			break;
		case NOT_REFINED:
			return afpChain;
		}
		
		try {
			if (order == 1 && type == SymmetryType.CLOSED) return afpChain;
			else afpChain = refiner.refine(afpAlignments, ca1, ca2, order);
		} catch (RefinerFailedException e) {
			e.printStackTrace();
		}
		
	//STEP 4: symmetry alignment optimization
		if (params.getOptimization()){
			//Perform several optimizations in different threads
			try {
				ExecutorService executor = Executors.newCachedThreadPool();
				List<Future<AFPChain>> afpFuture = new ArrayList<Future<AFPChain>>();
				int seed = params.getSeed();
				
				//Repeat the optimization 5 times in parallel, to obtain a more robust result.
				for (int rep=0; rep<10; rep++){
					Callable<AFPChain> worker = new SymmOptimizer(afpChain, ca1, type, seed+rep);
		  			Future<AFPChain> submit = executor.submit(worker);
		  			afpFuture.add(submit);
				}
				
				//When all the optimizations are finished take the one with the best result (best MC-score)
				afpChain.setAlignScore(0);
				for (int rep=0; rep<afpFuture.size(); rep++){
					if (afpFuture.get(rep).get().getAlignScore() > afpChain.getAlignScore()){
						afpChain = afpFuture.get(rep).get();
					}
				}
				executor.shutdown();
					
			} catch (InterruptedException e) {
				e.printStackTrace();
			} catch (ExecutionException e) {
				e.printStackTrace();
			} catch (RefinerFailedException e) {
				e.printStackTrace();
			}
		}
		// copy last distance matrix over for visualization
		/*if(lastMatrix != null) {
			for(i=1;i<20;i++)
				lastMatrix.set(i, i, 10);
			afpChain.setDistanceMatrix(  lastMatrix );
		}*/
		return afpChain;
	}

	@Override
	public ConfigStrucAligParams getParameters() {
		return params;
	}

	@Override
	public void setParameters(ConfigStrucAligParams parameters) {
		if (!(parameters instanceof CESymmParameters)) {
			throw new IllegalArgumentException(
					"Need to provide CESymmParameters, but provided "
							+ parameters.getClass().getName());
		}

		params = (CESymmParameters) parameters;
	}

	@Override
	public String getAlgorithmName() {
		return algorithmName;
	}

	@Override
	public String getVersion() {
		return version;
	}
	
	public static boolean isSignificant(AFPChain afpChain, Atom[] ca1) throws StructureException {

		// TM-score cutoff
		if (afpChain.getTMScore() < symmetryThreshold) return false;

		// sequence-function order cutoff
		int order = 1;
			try {
				OrderDetector orderDetector = new SequenceFunctionOrderDetector(8, 0.4f);
				order = orderDetector.calculateOrder(afpChain, ca1);
			} catch (OrderDetectionFailedException e) {
				e.printStackTrace();
				// try another method
			}

		if (order > 1) return true;

		// angle order cutoff
		RotationAxis rot = new RotationAxis(afpChain);
		order = rot.guessOrderFromAngle(Math.toRadians(1.0), 8);
		if (order > 1) return true;
		
		// asymmetric
		return false;
	}
	
	public boolean isSignificant() throws StructureException {
		return isSignificant(this.afpChain,this.ca1);
	}

	/**
	 * If available, get the list of subalignments.
	 * 
	 * Should be length one unless {@link CESymmParameters#getMaxSymmOrder()}
	 * was set.
	 * 
	 * 
	 * @return the afpAlignments
	 */
	public List<AFPChain> getAfpAlignments() {
		return afpAlignments;
	}

	@Override
	public MultipleAlignment align(List<Atom[]> atomArrays) throws StructureException {
		
		if (params == null)	params = new CESymmParameters();
		return align(atomArrays, params);
	}

	@Override
	public MultipleAlignment align(List<Atom[]> atomArrays, Object params) throws StructureException {
		
		if (atomArrays.size() != 1) 
			throw new IllegalArgumentException("For symmetry analysis only one Structure is needed, "+atomArrays.size()+" given.");
		
		AFPChain afp = align(atomArrays.get(0), atomArrays.get(0), params);
		MultipleAlignment msa = new MultipleAlignmentEnsembleImpl(afp, ca1, ca2).getMultipleAlignments().get(0);
		
		return msa;
	}
}
