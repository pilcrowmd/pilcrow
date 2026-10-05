# Eigenvalues and diagonalisation

**Lecture 4.** A square matrix acts on most vectors by turning and stretching them. Some special vectors are only stretched. These are the eigenvectors.

## The definition

Let $A$ be an $n \times n$ matrix. A non-zero vector $v$ is an **eigenvector** of $A$ with **eigenvalue** $\lambda$ if

$$
A v = \lambda v
$$

Rearranging gives $(A - \lambda I)v = 0$, which has a non-zero solution exactly when

$$
\det(A - \lambda I) = 0
$$

This is the **characteristic equation**. Its roots are the eigenvalues of $A$.

## A worked example

Take the matrix

$$
A = \begin{pmatrix} 4 & 1 \\ 2 & 3 \end{pmatrix}
$$

Then $\det(A - \lambda I) = (4 - \lambda)(3 - \lambda) - 2$, which simplifies to

$$
\lambda^{2} - 7\lambda + 10 = (\lambda - 2)(\lambda - 5) = 0
$$

so the eigenvalues are $\lambda_1 = 2$ and $\lambda_2 = 5$. Solving $(A - \lambda I)v = 0$ for each gives

$$
v_1 = \begin{pmatrix} 1 \\ -2 \end{pmatrix}, \qquad v_2 = \begin{pmatrix} 1 \\ 1 \end{pmatrix}
$$

As a check, $\operatorname{tr} A = 4 + 3 = \lambda_1 + \lambda_2$ and $\det A = 12 - 2 = \lambda_1 \lambda_2$.

## Diagonalisation

> **Theorem.** If an $n \times n$ matrix $A$ has $n$ linearly independent eigenvectors $v_1, \dots, v_n$, then $A = P D P^{-1}$, where the columns of $P$ are the eigenvectors and $D$ is the diagonal matrix of eigenvalues.

For our example,

$$
P = \begin{pmatrix} 1 & 1 \\ -2 & 1 \end{pmatrix}, \qquad D = \begin{pmatrix} 2 & 0 \\ 0 & 5 \end{pmatrix}
$$

## Why it matters

Powers become easy. Since $A^{k} = P D^{k} P^{-1}$ and $D^{k}$ just raises each diagonal entry to the power $k$:

$$
D^{k} = \begin{pmatrix} 2^{k} & 0 \\ 0 & 5^{k} \end{pmatrix}
$$

The same idea solves systems of linear differential equations, and it is behind many methods for ranking and data compression.

## Exercises

1. Find the eigenvalues of $\begin{pmatrix} 3 & 0 \\ 0 & -1 \end{pmatrix}$.
2. Show that the eigenvalues of a triangular matrix are its diagonal entries.
3. Compute $A^{3}$ for the example above using $P D^{3} P^{-1}$.
