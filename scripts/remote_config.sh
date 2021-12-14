for i in {4..15}
do
	scp -r ~/spark "node"$i:~/
	scp -r ~/fractal "node"$i:~/
done
