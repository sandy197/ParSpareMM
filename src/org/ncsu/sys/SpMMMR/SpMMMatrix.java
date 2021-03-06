package org.ncsu.sys.SpMMMR;

import org.apache.hadoop.ipc.GenericMatrix;
import org.ncsu.sys.SpMM.SpDCSC;

public class SpMMMatrix implements GenericMatrix<SpDCSC> {

	private SpDCSC spMat;
	private boolean isMatrixSet = false;
	
	public SpMMMatrix(SpDCSC matrix){
		spMat = matrix;
		isMatrixSet = true;
	}
	
	@Override
	public SpDCSC getMatrix() {
		if(isMatrixSet)
			return spMat;
		return null;
	}
	@Override
	public void setMatrix(SpDCSC matrix) {
		spMat = matrix;
		
	}
	@Override
	public boolean isMatrixSet() {
		return isMatrixSet;
	}
	@Override
	public void setMatrixSet(boolean isMatrixSet) {
		this.isMatrixSet = isMatrixSet;		
	}
	
	
	
	
}
