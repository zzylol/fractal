for i in {4..15}
do
	scp -r ~/fractal "node"$i:~/
done
