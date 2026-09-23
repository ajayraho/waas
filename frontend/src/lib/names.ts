const FIRST = [
  'Aarav', 'Diya', 'Kabir', 'Meera', 'Rohan', 'Ananya', 'Vihaan', 'Isha', 'Arjun', 'Sara',
  'Leo', 'Maya', 'Noah', 'Zoe', 'Omar', 'Lena', 'Ravi', 'Nina', 'Theo', 'Priya',
  'Ivy', 'Kai', 'Tara', 'Dev', 'Aisha', 'Ethan', 'Lila', 'Yash', 'Mila', 'Veer',
]

export function randomName() {
  return FIRST[Math.floor(Math.random() * FIRST.length)]
}
