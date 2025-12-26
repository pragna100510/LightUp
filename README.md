# 💡 LightUp (Akari) Puzzle Game – Java

## 📌 Overview

**LightUp (Akari)** is a logic-based puzzle game implemented in **Java** using **Swing** for the graphical user interface. The objective of the game is to place light bulbs on a grid so that all empty cells are illuminated, while respecting numbered constraints and avoiding bulb conflicts.

This project goes beyond a basic implementation by integrating **intelligent solving logic, graph algorithms, and interactive gameplay features**, making it both an academic and practical demonstration of problem-solving in Java.

---

## 🎯 Features

* 🧩 **Interactive LightUp (Akari) Puzzle Gameplay**
* 🖥️ **Java Swing GUI** with intuitive controls
* 🤖 **Smart AI Assistance** for logical bulb placement
* 🔄 **Undo / Redo functionality** for better user experience
* 🧠 **Deterministic Puzzle Solver**
* 🗂️ **Graph-based Analysis** of blank-cell connectivity
* 🔍 **BFS / DFS traversal** for component checking
* ⚡ **Rule Enforcement Engine** (no bulb conflicts, correct illumination)
* 🧪 **Puzzle Validation & Auto-correction**

---

## 🛠️ Technologies Used

* **Language:** Java
* **GUI Framework:** Java Swing (AWT)
* **Core Concepts:**

  * Object-Oriented Programming (OOP)
  * Graph Algorithms (BFS, DFS)
  * Stack-based Undo/Redo
  * Game Logic & Constraint Satisfaction

---

## 🧠 Game Rules (LightUp / Akari)

1. Light bulbs illuminate all cells horizontally and vertically until blocked by a wall.
2. No two bulbs may illuminate each other.
3. All empty cells must be illuminated.
4. Numbered black cells indicate exactly how many bulbs must be placed adjacent to them.

---

## 📂 Project Structure

```
LightUp.java        # Main game logic and GUI
```

> *(All logic, AI, and UI are encapsulated within the project source files.)*

---

## ▶️ How to Run

1. **Ensure Java is installed** (JDK 8 or above)
2. Compile the program:

   ```bash
   javac LightUp.java
   ```
3. Run the application:

   ```bash
   java LightUp
   ```

---

## 🚀 Future Enhancements

* Difficulty levels (Easy / Medium / Hard)
* Random puzzle generation with guaranteed uniqueness
* Timer and scoring system
* Hint system with step-by-step reasoning
* Save / Load puzzle states

---

## 📚 Academic Relevance

This project demonstrates:

* Application of **data structures and algorithms** in game logic
* Use of **graph traversal techniques** in constraint satisfaction problems
* Strong **OOP design principles**
* Practical **GUI development** using Java Swing

Suitable for:

* Data Structures mini-projects
* Java course projects
* AI-assisted logic game demonstrations

---

## 📜 License

This project is intended for **educational and academic use**. Feel free to fork and enhance it with proper attribution.

---

⭐ If you find this project useful, consider starring the repository!
