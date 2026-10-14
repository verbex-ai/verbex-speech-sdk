/**
 * A single-consumer async queue: frames are pushed as they arrive and read with `for await`.
 * Ending it with an error makes every read after the buffered items throw that error.
 */
export class AsyncQueue<T> implements AsyncIterable<T> {
  #items: T[] = [];
  #waiters: Array<{ resolve: (r: IteratorResult<T>) => void; reject: (e: unknown) => void }> = [];
  #ended = false;
  #error: unknown = undefined;

  get ended(): boolean {
    return this.#ended;
  }

  push(item: T): void {
    if (this.#ended) return;
    const waiter = this.#waiters.shift();
    if (waiter) waiter.resolve({ value: item, done: false });
    else this.#items.push(item);
  }

  end(error?: unknown): void {
    if (this.#ended) return;
    this.#ended = true;
    this.#error = error;
    for (const w of this.#waiters.splice(0)) {
      if (error !== undefined) w.reject(error);
      else w.resolve({ value: undefined, done: true });
    }
  }

  next(): Promise<IteratorResult<T>> {
    if (this.#items.length) return Promise.resolve({ value: this.#items.shift()!, done: false });
    if (this.#ended) return this.#error !== undefined ? Promise.reject(this.#error) : Promise.resolve({ value: undefined, done: true });
    return new Promise((resolve, reject) => this.#waiters.push({ resolve, reject }));
  }

  [Symbol.asyncIterator](): AsyncIterator<T> {
    return {
      next: () => this.next(),
      // Breaking out of a loop stops reading; it does not end the session behind the queue.
      return: async () => ({ value: undefined, done: true }),
    };
  }
}
