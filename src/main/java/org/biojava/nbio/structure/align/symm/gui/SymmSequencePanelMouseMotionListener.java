package org.biojava.nbio.structure.align.symm.gui;

import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.align.gui.aligpanel.AFPChainCoordManager;
import org.biojava.nbio.structure.gui.events.AlignmentPositionListener;
import org.biojava.nbio.structure.gui.util.AlignedPosition;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.ArrayList;
import java.util.List;

public class SymmSequencePanelMouseMotionListener implements MouseMotionListener, MouseListener {

	SymmSequencePanel parent;

	List<AlignmentPositionListener> aligPosListeners;
	int prevPos;

	boolean isDragging ;
	AlignedPosition selectionStart ;
	AlignedPosition selectionEnd;
	boolean selectionLocked;

	public SymmSequencePanelMouseMotionListener(SymmSequencePanel parent){
		this.parent = parent;
		aligPosListeners = new ArrayList<AlignmentPositionListener>();
		prevPos = -1;
		isDragging = false;
		selectionStart = null;
		selectionEnd = null;
		selectionLocked = false;
	}

	public void addAligPosListener(AlignmentPositionListener li){
		aligPosListeners.add(li);
	}

	@Override
	public void mouseDragged(MouseEvent e) {
		
		AlignedPosition pos = getCurrentAlignedPosition(e);
		
		if ( pos == null)
			return;

		int p = pos.getPos1();
			
		if ( prevPos == p && isDragging) {

			return;
		}

		if ( ! isDragging) {
			isDragging = true;
			setSelectionLock(true);
		}
		
		if ( selectionStart == null)
			selectionStart = pos;
		if ( selectionEnd == null)
			selectionEnd = pos;

		if (p <= selectionStart.getPos1()) {
			//selectionEnd = selectionStart;			
			selectionStart = pos;
			
		} else {
			selectionEnd = pos;
		}
		
		//System.out.println("sel start : " + selectionStart + " - " + selectionEnd);
		
		if ( ! keyPressed(e)) {
			triggerRangeSelected(selectionStart, selectionEnd);
		} else {
			triggerRangeSelected(selectionStart, selectionEnd);
			//triggerToggleRange(selectionStart, selectionEnd);
		}
		prevPos = p;
	}


	private boolean keyPressed(MouseEvent e) {
		if ( e.isShiftDown() || e.isControlDown() || e.isAltDown())
			return true;
		return false;
	}

	private void triggerRangeSelected(AlignedPosition start,
			AlignedPosition end) {		
		for (AlignmentPositionListener li : aligPosListeners){
			li.rangeSelected(start, end);
		}
	}
	public void triggerSelectionLocked(boolean b) {
		selectionLocked = b;
		for (AlignmentPositionListener li : aligPosListeners){
			if ( b)
				li.selectionLocked();
			else 
				li.selectionUnlocked();
		}

	}
	@Override
	public void mouseMoved(MouseEvent e) {
		if ( selectionLocked)
			return;
		AlignedPosition pos = getCurrentAlignedPosition(e);
		if ( pos == null)
			return;

		triggerMouseOverPosition(pos);




	}

	private void triggerMouseOverPosition(AlignedPosition pos) {
		for (AlignmentPositionListener li : aligPosListeners){
			li.mouseOverPosition(pos);
		}


	}

	private AlignedPosition getCurrentAlignedPosition(MouseEvent e){
		
		AFPChainCoordManager coordManager = parent.getCoordManager();
		
		int aligSeq = coordManager.getAligSeq(e.getPoint());

		// we are over a position in the sequences
		if (aligSeq == -1) return null;

		//get sequence positions
		int seqPos = coordManager.getSeqPos(aligSeq, e.getPoint());

		if ( seqPos < 0) return null;

		Atom[] ca1 = parent.getCa1();

		if (seqPos >= ca1.length) {
			//System.err.println("seqpos " + seqPos +" >= " + afpChain.getAlnLength());
			return null;
		}

		//System.out.println("alignment " + aligSeq + " " + seqPos + " : ");
		AlignedPosition pos = new AlignedPosition();
		pos.setPos1(seqPos);
		pos.setPos2(seqPos);

		//pos.setEquivalent(AlignedPosition.EQUIVALENT);
		
		return pos;
	}

	public void destroy() {
		aligPosListeners.clear();
		parent = null;
	}

	@Override
	public void mouseClicked(MouseEvent e) {

	}

	private void triggerToggleSelection(AlignedPosition pos) {
		for (AlignmentPositionListener li : aligPosListeners){
			li.toggleSelection(pos);
		}
	}

	@Override
	public void mouseEntered(MouseEvent e) {
		// TODO Auto-generated method stub
	}

	@Override
	public void mouseExited(MouseEvent e) {
		// TODO Auto-generated method stub
	}

	@Override
	public void mousePressed(MouseEvent e) {
		
		selectionStart = null;
		selectionEnd = null;
		
		if ( ! keyPressed(e) ) {
			//System.out.println("mouse pressed " + e.isShiftDown() + " selection locked: " + selectionLocked);
			
			setSelectionLock(false);
			//System.out.println("selection unlocked by mousePressed");
			triggerSelectionLocked(false);
			
			AlignedPosition pos = getCurrentAlignedPosition(e);
			if ( pos != null) {
				prevPos = pos.getPos1();
			}
		}
	}


	private void setSelectionLock(boolean flag){
		selectionLocked = flag;
		triggerSelectionLocked(flag);
	}

	@Override
	public void mouseReleased(MouseEvent e) {

		isDragging = false;
		//System.out.println("mouse released... " + e.isShiftDown() + " selection locked:" + selectionLocked);
		if ( keyPressed(e)) {
			boolean keepOn = false;
			if ( ! selectionLocked)
				keepOn = true;
			setSelectionLock(true);
			
			// add to selection
			AlignedPosition pos = getCurrentAlignedPosition(e);
			if ( pos == null)
				return;
			
			if ( keepOn)
				triggerMouseOverPosition(pos);
			else
				triggerToggleSelection(pos);
			prevPos = pos.getPos1() ; 
		}
	}

}
