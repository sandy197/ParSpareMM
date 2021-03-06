package org.ncsu.sys.SpMMMR;

import java.io.IOException;
import java.util.List;

import org.apache.commons.collections.ResettableIterator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.GenericMatrix;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.join.ResetableIterator;
import org.ncsu.sys.SpMM.SpDCSC;
import org.ncsu.sys.SpMM.StackEntry;
import org.ncsu.sys.SpMMMR.SpMMTypes.IndexPair;
import org.ncsu.sys.SpMMMR.SpMMTypes.Key;
import org.ncsu.sys.SpMMMR.SpMMTypes.Value;

public class SpMMReducer extends Reducer<Key, Value, Key, Value> {
	
	private GenericMatrix<?> A, B;
	private Key indexPair;
	private Value el = new Value();
	
	private static final boolean DEBUG = false;

	private boolean useTaskPool;
	private boolean isSparseMM;
	private String inputPathA;
	private String inputPathB;
	private String outputDirPath;
	private String tempDirPath;
	private static int R1;
	private static int R2;
	private static int I;
	private static int K;
	private static int J;
	private static int IB;
	private static int KB;
	private static int JB;
	
	private static int NIB;
	private static int NKB;
	private static int NJB;
	
	private static boolean useM;
	
	private static int lastIBlockNum;
	private static int lastIBlockSize;
	private static int lastKBlockNum;
	private static int lastKBlockSize;
	private static int lastJBlockNum;
	private static int lastJBlockSize;
	
	private int sib, skb, sjb;

	public void reduce(SpMMTypes.Key key, Iterable<SpMMTypes.Value> values, Context context)	
			throws IOException, InterruptedException {
		
		 if (DEBUG) printReduceInputKey(key);
	      int ib, kb, jb, nz1, nz2;
	      int sum = 0;
	      //job 2 reduce function
	      if(key.index2 < 0){
	    	  //sum up all the values and write
	    	  for(Value val : values){
	    		  sum += val.v;
	    	  }
	    	  el.set(sum);
	    	  context.write(key, el);
	    	  return;
	      }
	      
          ib = key.index1;
          kb = key.index2;
          jb = key.index3;
          if (key.m == 0) {
            sib = ib;
            skb = kb;
            A = build(values, IB, KB, context);
          } else {
            //if (ib != sib || kb != skb) return;
            //bColDim = getDim(jb, lastJBlockNum, JB, lastJBlockSize);
            B = buildOrGet(values, KB, JB, context);
            //multiply & emit
            //support building normal matrix as well.
            multiplyAndEmit(context, ib, jb);
          }
	}

	private void multiplyAndEmit(Context context, int ib2,
			int jb2) {
		if(isSparseMM){
			SpDCSC a, b;
			a = (SpDCSC) A.getMatrix();
			b = (SpDCSC) B.getMatrix();
			List<StackEntry> multStack = a.SpMatMultiply(b);
			for(StackEntry se : multStack){
				if(se.value != 0){
					indexPair = new Key();
					indexPair.index1 = ib2*IB + se.key.first;
					indexPair.index2 = -1;
					indexPair.index3 = jb2*JB + se.key.second;
					el.set(se.value);
					try {
						context.write(indexPair, el);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		}
		else{
			//regular matrix multiply
			int[][] a,b;
			a = (int[][]) A.getMatrix();
			b = (int[][]) B.getMatrix();
			int ibase = ib2*IB;
			int jbase = jb2*JB;
			long multiplyTime = 0;
			long writingTime = 0;
			for (int i = 0; i < IB; i++) {
				for (int j = 0; j < JB; j++) {
					int sum = 0;
					long start_m = System.nanoTime();
					//increasing the number of iterations to check 
					//for a 5X increase in multiplication time
					for (int i_jff = 0; i_jff < 1000; i_jff++){
					for (int k = 0; k < KB; k++) {
						//srkandul
						if(a[i][k] != 0 && b[k][j] != 0){
							sum += b[i][k] * b[k][j];
						}
					}
					}
					long end_m = System.nanoTime();
					multiplyTime += end_m - start_m;
					long start = System.nanoTime();
					if (sum != 0) {
						indexPair.index1 = ibase + i;
						indexPair.index2 = -1;
						indexPair.index3 = jbase + j;
						el.set(sum);
						try {
							context.write(indexPair, el);
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					long end = System.nanoTime();
					writingTime += end - start;
				}
			}
		}
	}
	
	private GenericMatrix<?> buildOrGet(Iterable<Value> values, int kB2,
			int jB2, Context context) {
		if(useTaskPool){
			return context.getMatrix();
		}
		else{
			GenericMatrix<?> genMatrix = build(values, kB2, jB2, context);
			context.setMatrix(genMatrix);
			return genMatrix;
		}
	}

	private GenericMatrix<?> build(Iterable<Value> values, int m, int n, Context context) {
		if(isSparseMM)
			return new SpMMMatrix(new SpDCSC(values, m, n));
		else
			return new RegMatrix(build_orig(m, n, values));
	}
	
	private int[][] build_orig(int rowDim, int colDim,
            Iterable<Value> valueList)
    {
		int[][] matrix = new int[rowDim][colDim];
		int nonZeros = 0;
		for (int rowIndex = 0; rowIndex < rowDim; rowIndex++)
			for (int colIndex = 0; colIndex < colDim; colIndex++)
				matrix[rowIndex][colIndex] = 0;
		for (Value value : valueList) {
			if (DEBUG) printReduceInputValue(value);
			matrix[value.index1][value.index2] = value.v;
			if(value.v != 0){
				nonZeros++;
			}
		}
		return matrix;
    }

	public void setup (Context context) {
		init(context);
		if (DEBUG) {
			System.out.println("##### Reduce setup");
			System.out.println("   I = " + I);
			System.out.println("   K = " + K);
			System.out.println("   J = " + J);
			System.out.println("   IB = " + IB);
			System.out.println("   KB = " + KB);
			System.out.println("   JB = " + JB);
		}
//		A = new int[IB][KB];
//		B = new int[KB][JB];
		
		sib = -1;
		skb = -1;
		sjb = -1;
	}
	
	private void init(JobContext context) {
		Configuration conf = context.getConfiguration();
		useTaskPool = conf.getBoolean("SpMM.useTaskPool", false);
		isSparseMM = conf.getBoolean("SpMM.isSparseMM", false);
		inputPathA = conf.get("SpMM.inputPathA");
		inputPathB = conf.get("SpMM.inputPathB");
		outputDirPath = conf.get("SpMM.outputDirPath");
		tempDirPath = conf.get("SpMM.tempDirPath");
		R1 = conf.getInt("SpMM.R1", 0);
		R2 = conf.getInt("SpMM.R2", 0);
		I = conf.getInt("SpMM.I", 0);
		K = conf.getInt("SpMM.K", 0);
		J = conf.getInt("SpMM.J", 0);
		IB = conf.getInt("SpMM.IB", 0);
		KB = conf.getInt("SpMM.KB", 0);
		JB = conf.getInt("SpMM.JB", 0);
		NIB = (I-1)/IB + 1;
		NKB = (K-1)/KB + 1;
		NJB = (J-1)/JB + 1;
		lastIBlockNum = NIB-1;
		lastIBlockSize = I - lastIBlockNum*IB;
		lastKBlockNum = NKB-1;
		lastKBlockSize = K - lastKBlockNum*KB;
		lastJBlockNum = NJB-1;
		lastJBlockSize = J - lastJBlockNum*JB;
	}
	      
  private void printReduceInputKey (SpMMTypes.Key key) {
		System.out.println("##### Reduce input: key = (" + key.index1 + "," + 
			key.index2 + "," + key.index3 + "," + key.m + ")");
	}
	
	private void printReduceInputValue (SpMMTypes.Value value) {
		System.out.println("##### Reduce input: value = (" + value.index1 + "," +
			value.index2 + "," + value.v + ")");
	}
	
	private void printReduceOutput (IndexPair indexPair, IntWritable el) {
		System.out.println("##### Reduce output: (" + indexPair.index1 + "," + 
			indexPair.index2 + ") " + el.get());
	}

}
